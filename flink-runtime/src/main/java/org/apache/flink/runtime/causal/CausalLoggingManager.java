/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.runtime.causal;

import org.apache.flink.runtime.causal.determinant.Determinant;
import org.apache.flink.runtime.causal.determinant.OrderDeterminant;
import org.apache.flink.runtime.causal.determinant.RNGDeterminant;
import org.apache.flink.runtime.state.CheckpointListener;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A CausalLog contains the determinant logs of all upstream operators and itself.
 */
public interface CausalLoggingManager extends CheckpointListener {


	List<VertexCausalLogDelta> getDeterminants();

	/*
	Encodes and appends to this tasks log
	 */
	void appendDeterminant(Determinant determinant);


	void processCausalLogDelta(VertexCausalLogDelta d);

	byte[] getDeterminantsOfVertex(VertexId vertexId);

	void enrichWithDeltas(DeterminantCarrier record, int targetChannel);

	void notifyCheckpointBarrier(long checkpointId);

	void notifyDownstreamFailure(int channel);

	//============== Services to simplify usage

    RandomService getRandomService();

	//============= Recovery Manager

	void registerSilenceable(Silenceable silenceable);

	void notifyDeterminantResponseEvent(DeterminantResponseEvent determinantResponseEvent);

	boolean hasDeterminantsToRecoverFrom();

	OrderDeterminant getRecoveryOrderDeterminant();

	RNGDeterminant getRecoveryRNGDeterminant();

	void stopRecovery();

	void startRecovery();

	boolean isRecovering();
}