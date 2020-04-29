/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional debugrmation
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
package org.apache.flink.runtime.inflightlogging;

import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class SubpartitionInFlightLogger implements InFlightLog {

	private static final Logger LOG = LoggerFactory.getLogger(SubpartitionInFlightLogger.class);

	private SortedMap<Long, List<Buffer>> slicedLog;

	public SubpartitionInFlightLogger() {
		clearLog();
	}


	public void log(Buffer buffer) {
		LOG.debug("Logged a new buffer for epoch {}", slicedLog.lastKey());
		getCurrentSlice().add(buffer.retainBuffer());
	}

	@Override
	public void clearLog() {

		slicedLog = new TreeMap<>();

		//Perhaps we should use array lists, initialized to the size of the previous epoch.
		slicedLog.put(0l, new LinkedList<>());
	}

	@Override
	public void logCheckpointBarrier(Buffer buffer, long checkpointId) {
		LOG.debug("Logging a checkpoint barrier buffer with id {}", checkpointId);
		getCurrentSlice().add(buffer.retainBuffer());
		slicedLog.put(checkpointId, new LinkedList<>());
	}

	private List<Buffer> getCurrentSlice() {
		return slicedLog.get(slicedLog.lastKey());
	}

	@Override
	public void notifyCheckpointComplete(long completedCheckpointId) {

		LOG.debug("Got notified of checkpoint {} completion\nCurrent log: {}", completedCheckpointId, representLogAsString(this.slicedLog));
		List<Long> toRemove = new LinkedList<>();

		//keys are in ascending order
		for (long epochId : slicedLog.keySet()) {
			if (epochId < completedCheckpointId) {
				toRemove.add(epochId);
				LOG.debug("Removing epoch {}", epochId);
			}
		}

		for (long checkpointBarrierId : toRemove) {
			List<Buffer> slice = slicedLog.remove(checkpointBarrierId);
			for (Buffer b : slice) {
				b.recycleBuffer();
			}
		}
	}

	@Override
	public SizedListIterator<Buffer> getInFlightFromCheckpoint(long checkpointId) {
		//The lower network stack recycles buffers, so for each replay, we must
		increaseReferenceCounts(checkpointId);
		return new ReplayIterator(checkpointId, slicedLog);
	}

	private void increaseReferenceCounts(Long checkpointId) {
		for (List<Buffer> list : slicedLog.tailMap(checkpointId).values())
			for (Buffer buffer : list)
				buffer.retainBuffer();


	}

	public static class ReplayIterator implements SizedListIterator<Buffer> {
		private long startKey;
		private long currentKey;
		private ListIterator<Buffer> currentIterator;
		private SortedMap<Long, List<Buffer>> logToReplay;
		private int numberOfBuffersLeft;

		public ReplayIterator(long lastCompletedCheckpointOfFailed, SortedMap<Long, List<Buffer>> logToReplay) {
			//Failed at checkpoint x, so we replay starting at epoch x
			this.startKey = lastCompletedCheckpointOfFailed;
			this.currentKey = lastCompletedCheckpointOfFailed;
			this.logToReplay = logToReplay.tailMap(lastCompletedCheckpointOfFailed);
			LOG.debug(" Getting iterator for checkpoint id {} with log state {} and sublog state {}", currentKey, representLogAsString(logToReplay), representLogAsString(this.logToReplay));
			this.currentIterator = this.logToReplay.get(currentKey).listIterator();
			numberOfBuffersLeft = this.logToReplay.values().stream().mapToInt(List::size).sum(); //add up the sizes
			LOG.debug("State of log: {}\nlog tailmap {}\nIterator creation {}: ", representLogAsString(logToReplay), representLogAsString(this.logToReplay), this.toString());
		}

		private void advanceToNextNonEmptyIteratorIfNeeded() {
			while (!currentIterator.hasNext() && currentKey < logToReplay.lastKey()) {
				this.currentIterator = logToReplay.get(++currentKey).listIterator();
				while (currentIterator.hasPrevious()) currentIterator.previous();
			}
		}

		private void advanceToPreviousNonEmptyIteratorIfNeeded() {
			while (!currentIterator.hasPrevious() && currentKey > logToReplay.firstKey()) {
				this.currentIterator = logToReplay.get(--currentKey).listIterator();
				while (currentIterator.hasNext()) currentIterator.next(); //fast forward the iterator
			}
		}

		@Override
		public boolean hasNext() {
			advanceToNextNonEmptyIteratorIfNeeded();
			return currentIterator.hasNext();
		}

		@Override
		public boolean hasPrevious() {
			advanceToPreviousNonEmptyIteratorIfNeeded();
			return currentIterator.hasPrevious();
		}

		@Override
		public Buffer next() {
			advanceToNextNonEmptyIteratorIfNeeded();
			Buffer toReturn = currentIterator.next();
			numberOfBuffersLeft--;
			return toReturn;
		}

		@Override
		public Buffer previous() {
			advanceToPreviousNonEmptyIteratorIfNeeded();
			Buffer toReturn = currentIterator.previous();
			numberOfBuffersLeft++;
			return toReturn;
		}

		@Override
		public int nextIndex() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int previousIndex() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void set(Buffer buffer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void add(Buffer buffer) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int numberRemaining() {
			return numberOfBuffersLeft;
		}

		@Override
		public String toString() {
			return "ReplayIterator{" +

				"startKey=" + startKey +
				", currentKey=" + currentKey +
				", currentIterator=" + currentIterator +
				", logToReplay=" + representLogAsString(logToReplay) +
				", numberOfBuffersLeft=" + numberOfBuffersLeft +
				", reduceCount=" + logToReplay.values().stream().mapToInt(List::size).sum() +
				", lists=" + logToReplay.values().stream().map(l -> "[" + l.stream().map(x -> "*").collect(Collectors.joining(", ")) + "]").collect(Collectors.joining("; ")) +
				'}';
		}

	}

	private static String representLogAsString(SortedMap<Long, List<Buffer>> toStringify) {
		return "{" + toStringify.entrySet().stream().map(e -> e.getKey() + " -> " + "[" + e.getValue().stream().map(x -> "*").collect(Collectors.joining(", ")) + "]").collect(Collectors.joining(", ")) + "}";
	}

}