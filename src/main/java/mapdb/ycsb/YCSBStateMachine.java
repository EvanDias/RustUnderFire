package mapdb.ycsb;

import mapdb.MapDB;
import mapdb.crud.CrudMessage;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import site.ycsb.Status;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class YCSBStateMachine extends BaseStateMachine {

    public MapDB mapServer;

    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId,
                           RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        this.storage.init(raftStorage);
        load(storage.getLatestSnapshot());

        String sv = server.toString();
        String[] svSplitted = sv.split(":");

        this.mapServer = new MapDB("src/main/java/mapdb/files/" + svSplitted[0] + ".db", "map" + svSplitted[0]);
    }


    @Override
    public void reinitialize() throws IOException {
        load(storage.getLatestSnapshot());
    }

    // Load the state of the state machine from the storage.
    private long load(SingleFileSnapshotInfo snapshot) throws IOException {

        //check the snapshot nullity
        if (snapshot == null) {
            LOG.warn("The snapshot info is null.");
            return RaftLog.INVALID_LOG_INDEX;
        }

        //check the existence of the snapshot file
        final File snapshotFile = snapshot.getFile().getPath().toFile();
        if (!snapshotFile.exists()) {
            LOG.warn("The snapshot file {} does not exist for snapshot {}",
                    snapshotFile, snapshot);
            return RaftLog.INVALID_LOG_INDEX;
        }

        //load the TermIndex object for the snapshot using the file name pattern of
        // the snapshot
        final TermIndex last =
                SimpleStateMachineStorage.getTermIndexFromSnapshotFile(snapshotFile);

        return last.getIndex();
    }


    /**
     *  This class maintain a HTreeMap (MapDB) and accept many commands/requests:
     *  GET, KEYSET and SIZE. They are ReadOnly commands which will be handled by
     *  this method
     * @param request client request that arrive in a String/Json format
     * @return message reply to client
     */
    @Override
    public CompletableFuture<Message> query(Message request)  {

        YCSBMessage ycsbRequest = YCSBMessage.deserializeByteStringToObject(ByteString.copyFrom(request.getContent().toByteArray()));
        YCSBMessage ycsbReply = YCSBMessage.newErrorMessage("");

        String requestedValue;

        switch (Objects.requireNonNull(ycsbRequest).getType()) {
            case READ:
                requestedValue = this.mapServer.getValue(ycsbRequest.getKey()); // returns the value contained in the mapdb

                if(requestedValue.equals(""))
                    ycsbReply = YCSBMessage.newErrorMessage("There is no record for key: " + ycsbRequest.getKey());
                else
                    ycsbReply = YCSBMessage.newReadResponse(requestedValue, YCSBMessage.ReplyStatus.OK);

                break;

            default:
                return CompletableFuture.completedFuture(
                        Message.valueOf("Invalid request type!"));
        }

        final long index = getLastAppliedTermIndex().getIndex();
        final long term = getLastAppliedTermIndex().getTerm();

        LOG.info("| Operation: {} | Index: {} | Term: {} | LogEntry: TODO | Reply: {}", ycsbRequest.getType().toString(), index, term, requestedValue);
        // TODO: MSG TO STRING

        return CompletableFuture.completedFuture(
                    Message.valueOf(ycsbReply.serializeObjectToByteString()));
    }

    /**
     *  This class maintain a HTreeMap (MapDB) and accept many commands/requests:
     *  PUT, UPDATE AND DELETE. They are transactional commands which will be handled by
     *  this method
     * @param trx committed entry coming from the RAFT log from the leader
     * @return message reply to client
     */
    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        final RaftProtos.LogEntryProto entry = trx.getLogEntry();

        YCSBMessage ycsbRequest = YCSBMessage.deserializeByteStringToObject(ByteString.copyFrom(entry.toByteArray()));
        YCSBMessage ycsbReply = YCSBMessage.newErrorMessage("");
        String requestedValue;

        //check if the command is valid
        String logData = entry.getStateMachineLogEntry().getLogData()
                .toString(Charset.defaultCharset());

        //trx.getLogEntry().getMetadataEntry().toString();

        CrudMessage requestMsg = CrudMessage.deserializeByteStringToObject(ByteString.copyFrom(entry.getStateMachineLogEntry().getLogData().toByteArray()));

        //update the last applied term and index
        final long index = entry.getIndex();
        final long term = entry.getTerm();
        updateLastAppliedTermIndex(term, index);

        String operation;

        switch (Objects.requireNonNull(requestMsg).getType()) {
            case CREATE:
                this.mapServer.putValue(ycsbRequest.getKey(), ycsbRequest.getValue()); // returns the value contained in the mapdb

                ycsbReply = YCSBMessage.newInsertResponse(YCSBMessage.ReplyStatus.OK);

                break;

            case UPDATE:
                this.mapServer.updateValue(requestMsg.getKey(), requestMsg.getValue());
                break;

            default:
                return CompletableFuture.completedFuture(
                        Message.valueOf("Invalid request type!"));
        }

        // return success to client
        final CompletableFuture<Message> f =
                CompletableFuture.completedFuture(Message.valueOf("Operation " + requestMsg.getType().toString() + " successfully performed!"));

        // if leader, log the incremented value and it's log index
        if (trx.getServerRole() == RaftProtos.RaftPeerRole.LEADER) {
            LOG.info("| Operation: {} | Index: {} | Term: {} | LogEntry:  |", requestMsg.getType().toString(), index, term);
        }

        return f;
    }


}
