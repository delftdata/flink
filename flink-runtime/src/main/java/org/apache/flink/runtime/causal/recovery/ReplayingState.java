/*
 *
 *
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 *
 *
 */

package org.apache.flink.runtime.causal.recovery;

import com.google.common.collect.Table;
import org.apache.flink.runtime.causal.DeterminantResponseEvent;
import org.apache.flink.runtime.causal.determinant.*;
import org.apache.flink.runtime.causal.log.job.CausalLogID;
import org.apache.flink.runtime.event.InFlightLogRequestEvent;
import org.apache.flink.runtime.io.network.partition.PipelinedSubpartition;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.shaded.netty4.io.netty.buffer.ByteBuf;
import org.apache.flink.shaded.netty4.io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * In this state we do the actual process of recovery. Once done transition to {@link RunningState}
 * <p>
 * A downstream failure in this state does not matter, requests simply get added to the queue of unanswered requests.
 * Only when we finish our recovery do we answer those requests.
 * <p>
 * Upstream failures in this state mean that perhaps the upstream will not have sent all buffers. This means we must
 * resend
 * replay requests.
 */
public class ReplayingState extends AbstractState {

	private static final Logger LOG = LoggerFactory.getLogger(ReplayingState.class);

	DeterminantEncoder determinantEncoder;

	ByteBuf mainThreadRecoveryBuffer;

	List<Thread> recoveryThreads;

	Determinant nextDeterminant;

	Queue<Determinant>[] reuseCache;

	public ReplayingState(RecoveryManager context, DeterminantResponseEvent determinantAccumulator) {
		super(context);

		logDebug("Entered replaying state with determinants: {}", determinantAccumulator);
		determinantEncoder = context.causalLog.getDeterminantEncoder();

		setupDeterminantCache();

		createSubpartitionRecoveryThreads(determinantAccumulator);
		for (Thread t : recoveryThreads)
			t.start();

		this.mainThreadRecoveryBuffer =
			determinantAccumulator.getDeterminants().get(new CausalLogID(context.vertexGraphInformation.getThisTasksVertexID().getVertexID()));
	}

	private void setupDeterminantCache() {
		reuseCache = new Queue[6];
		//We use this cache to avoid object creation during replay
		//We require two determinants of each type because while one is being processed, the next may already be
		// a determinant of the same type

		reuseCache[OrderDeterminant.getTypeTag()] = new ArrayDeque<>();
		reuseCache[OrderDeterminant.getTypeTag()].add(new OrderDeterminant());
		reuseCache[OrderDeterminant.getTypeTag()].add(new OrderDeterminant());

		reuseCache[TimestampDeterminant.getTypeTag()] = new ArrayDeque<>();
		reuseCache[TimestampDeterminant.getTypeTag()].add(new TimestampDeterminant());
		reuseCache[TimestampDeterminant.getTypeTag()].add(new TimestampDeterminant());

		reuseCache[RNGDeterminant.getTypeTag()] = new ArrayDeque<>();
		reuseCache[RNGDeterminant.getTypeTag()].add(new RNGDeterminant());
		reuseCache[RNGDeterminant.getTypeTag()].add(new RNGDeterminant());

		reuseCache[TimerTriggerDeterminant.getTypeTag()] = new ArrayDeque<>();
		reuseCache[TimerTriggerDeterminant.getTypeTag()].add(new TimerTriggerDeterminant());
		reuseCache[TimerTriggerDeterminant.getTypeTag()].add(new TimerTriggerDeterminant());

		reuseCache[SourceCheckpointDeterminant.getTypeTag()] = new ArrayDeque<>();
		reuseCache[SourceCheckpointDeterminant.getTypeTag()].add(new SourceCheckpointDeterminant());
		reuseCache[SourceCheckpointDeterminant.getTypeTag()].add(new SourceCheckpointDeterminant());

		reuseCache[IgnoreCheckpointDeterminant.getTypeTag()] = new ArrayDeque<>();
		reuseCache[IgnoreCheckpointDeterminant.getTypeTag()].add(new IgnoreCheckpointDeterminant());
		reuseCache[IgnoreCheckpointDeterminant.getTypeTag()].add(new IgnoreCheckpointDeterminant());
	}

	public void executeEnter() {
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		context.readyToReplayFuture.complete(null);//allow task to start running
	}

	private void createSubpartitionRecoveryThreads(DeterminantResponseEvent determinantResponseEvent) {

		recoveryThreads = new LinkedList<>();

		CausalLogID id = new CausalLogID(context.vertexGraphInformation.getThisTasksVertexID().getVertexID());
		for (Table.Cell<IntermediateResultPartitionID, Integer, PipelinedSubpartition> cell :
			context.subpartitionTable.cellSet()) {
			id.replace(cell.getRowKey().getLowerPart(), cell.getRowKey().getUpperPart(),
				cell.getColumnKey().byteValue());
			ByteBuf subpartitionBuf = determinantResponseEvent.getDeterminants().get(id);
			ByteBuf recoveryBuffer = Unpooled.EMPTY_BUFFER;
			if (subpartitionBuf != null)
				recoveryBuffer = subpartitionBuf;
			PipelinedSubpartition subpartition = cell.getValue();

			Thread t = new SubpartitionRecoveryThread(recoveryBuffer, subpartition, context, cell.getRowKey(),
				cell.getColumnKey());
			t.setDaemon(true);
			recoveryThreads.add(t);

			logDebug("Created recovery thread for Partition {} subpartition index {} with buffer {}", cell.getRowKey(),
				cell.getColumnKey(), recoveryBuffer);
		}
	}


	@Override
	public void notifyNewInputChannel(InputChannel inputChannel, int consumedSubpartitionIndex,
									  int numberOfBuffersRemoved) {
		//we got notified of a new input channel while we were replaying
		//This means that  we now have to wait for the upstream to finish recovering before we do.
		//Furthermore, we have to resend the inflight log request, and ask to skip X buffers

		logDebug("Got notified of new input channel event, while in state " + this.getClass() + " requesting " +
			"upstream" +
			" " +
			"to replay and skip numberOfBuffersRemoved");
		if (!(inputChannel instanceof RemoteInputChannel))
			return;
		IntermediateResultPartitionID id = inputChannel.getPartitionId().getPartitionId();
		try {
			inputChannel.sendTaskEvent(new InFlightLogRequestEvent(id, consumedSubpartitionIndex,
				context.epochProvider.getCurrentEpochID(), numberOfBuffersRemoved));
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}


	//===================

	@Override
	public int replayRandomInt() {
		if (!(nextDeterminant instanceof RNGDeterminant))
			throw new RuntimeException("Unexpected Determinant type: Expected RNG, but got: " + nextDeterminant);
		int toReturn = ((RNGDeterminant) nextDeterminant).getNumber();
		recycleDeterminant(nextDeterminant);
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		return toReturn;
	}

	@Override
	public byte replayNextChannel() {
		if (!(nextDeterminant instanceof OrderDeterminant))
			throw new RuntimeException("Unexpected Determinant type: Expected Order, but got: " + nextDeterminant);

		byte toReturn = ((OrderDeterminant) nextDeterminant).getChannel();
		recycleDeterminant(nextDeterminant);
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		return toReturn;
	}

	@Override
	public long replayNextTimestamp() {
		if (!(nextDeterminant instanceof TimestampDeterminant))
			throw new RuntimeException("Unexpected Determinant type: Expected Timestamp, but got: " + nextDeterminant);

		long toReturn = ((TimestampDeterminant) nextDeterminant).getTimestamp();
		recycleDeterminant(nextDeterminant);
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		return toReturn;
	}

	@Override
	public void triggerAsyncEvent() {
		AsyncDeterminant asyncDeterminant = (AsyncDeterminant) nextDeterminant;
		int currentRecordCount = context.recordCounter.getRecordCount();

		LOG.info("Trigger async event, record count: {}, determinant record count: {}", currentRecordCount, asyncDeterminant.getRecordCount());
		if (currentRecordCount != asyncDeterminant.getRecordCount())
			throw new RuntimeException("Current record count is not the determinants record count. Current: " + currentRecordCount + ", determinant: " + asyncDeterminant.getRecordCount());


		context.recordCountTargetForceable.setRecordCountTarget(-1);
		//Prepare next first, because the async event, in being processed, may require determinants
		//But do not yet recycle the asyncDeterminant, as we must process it first
		prepareNext();
		asyncDeterminant.process(context);
		//Now that we have used it, we may recycle it
		recycleDeterminant(asyncDeterminant);
		if (nextDeterminant == null)
			finishReplaying();


	}

	private void recycleDeterminant(Determinant determinant) {
		if (determinant != null)
			reuseCache[determinant.getTag()].add(determinant);
	}

	private void prepareNext() {
		nextDeterminant = null;
		if (mainThreadRecoveryBuffer != null && mainThreadRecoveryBuffer.isReadable()) {
			try {
				nextDeterminant = determinantEncoder.decodeNext(mainThreadRecoveryBuffer, reuseCache);
				LOG.info("Task {} - prepareNext {}", context.getTaskVertexID().getVertexID(), nextDeterminant);
			}catch (Exception e){
				LOG.error("Task {} - Exception : {}", context.getTaskVertexID().getVertexID(), e);
			}

			if (nextDeterminant instanceof AsyncDeterminant)
				context.recordCountTargetForceable.setRecordCountTarget(((AsyncDeterminant) nextDeterminant).getRecordCount());
		}
	}

	private void finishReplaying() {

		//Safety check that recovery brought us to the exact same causal log state as pre-failure
		assert mainThreadRecoveryBuffer.capacity() ==
			context.causalLog.threadLogLength(new CausalLogID(context.getTaskVertexID().getVertexID()));

		if (mainThreadRecoveryBuffer != null)
			mainThreadRecoveryBuffer.release();

		logDebug("Finished recovering main thread! Transitioning to RunningState!");
		context.setState(new RunningState(context));
	}

	@Override
	public String toString() {
		return "ReplayingState{}";
	}

	private static class SubpartitionRecoveryThread extends Thread {
		private final PipelinedSubpartition pipelinedSubpartition;
		private final ByteBuf recoveryBuffer;
		private final DeterminantEncoder determinantEncoder;
		private final RecoveryManager context;
		private final IntermediateResultPartitionID partitionID;
		private final int index;

		public SubpartitionRecoveryThread(ByteBuf recoveryBuffer, PipelinedSubpartition pipelinedSubpartition,
										  RecoveryManager context, IntermediateResultPartitionID partitionID,
										  int index) {
			this.recoveryBuffer = recoveryBuffer;
			this.pipelinedSubpartition = pipelinedSubpartition;
			this.determinantEncoder = context.causalLog.getDeterminantEncoder();
			this.context = context;
			this.partitionID = partitionID;
			this.index = index;

		}

		@Override
		public void run() {
			//1. Netty has been told that there is no data.
			context.numberOfRecoveringSubpartitions.incrementAndGet();
			if (recoveryBuffer.capacity() > 0) {
				BufferBuiltDeterminant reuse = new BufferBuiltDeterminant();
				Queue<Determinant>[] subpartCache = new Queue[7];
				subpartCache[BufferBuiltDeterminant.getTypeTag()] = new ArrayDeque<>();
				subpartCache[BufferBuiltDeterminant.getTypeTag()].add(reuse);
				//2. Rebuild in-flight log and subpartition state
				while (recoveryBuffer.isReadable()) {

					Determinant determinant;
					try {
						determinant = determinantEncoder.decodeNext(recoveryBuffer, subpartCache);
					} catch (Exception e) {
						LOG.error("Vertex {} - Recovery thread for partition {} index {} found exception {}",
							context.getTaskVertexID().getVertexID(), partitionID, index, e);
						throw e;
					}

					if (!(determinant instanceof BufferBuiltDeterminant))
						throw new RuntimeException("Vertex " + context.getTaskVertexID().getVertexID() + " - " +
							"Subpartition has corrupt recovery buffer, expected buffer built," +
							" " +
							"got: "
							+ determinant);
					BufferBuiltDeterminant bufferBuiltDeterminant = (BufferBuiltDeterminant) determinant;

					LOG.info("Vertex {} - Requesting to build and log buffer with {} bytes", context.getTaskVertexID().getVertexID(),
						bufferBuiltDeterminant.getNumberOfBytes());
					try {
						pipelinedSubpartition.buildAndLogBuffer(bufferBuiltDeterminant.getNumberOfBytes());
					} catch (InterruptedException e) {
						return;
					}
					subpartCache[BufferBuiltDeterminant.getTypeTag()].add(reuse);
				}
			}
			LOG.info("Vertex {} - Done recovering pipelined subpartition", context.getTaskVertexID().getVertexID());
			//Safety check that recovery brought us to the exact same state as pre-failure
			int logLengthAfterRecovery =
				context.causalLog.threadLogLength(new CausalLogID(context.getTaskVertexID().getVertexID(),
					partitionID.getLowerPart(), partitionID.getUpperPart(), (byte) index));
			assert recoveryBuffer.capacity() == logLengthAfterRecovery;

			// If there is a replay request, we have to prepare it, before setting isRecovering to true
			InFlightLogRequestEvent unansweredRequest =
				context.unansweredInFlighLogRequests.remove(partitionID, index);
			LOG.info("Checking for unanswered inflight request for this subpartition.");
			if (unansweredRequest != null) {
				LOG.info("There is an unanswered replay request for this subpartition.");
				pipelinedSubpartition.requestReplay(unansweredRequest.getCheckpointId(),
					unansweredRequest.getNumberOfBuffersToSkip());
			}

			//3. Tell netty to restart requesting buffers.
			pipelinedSubpartition.setIsRecoveringSubpartitionInFlightState(false);
			pipelinedSubpartition.notifyDataAvailable();
			recoveryBuffer.release();
			context.numberOfRecoveringSubpartitions.decrementAndGet();
			LOG.info("Subpartition is free to restart sending buffers.");

		}
	}

}
