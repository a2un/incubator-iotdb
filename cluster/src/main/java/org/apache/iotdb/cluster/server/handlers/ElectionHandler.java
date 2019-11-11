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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.server.handlers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.RaftService.AsyncClient.startElection_call;
import org.apache.iotdb.cluster.server.RaftServer;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElectionHandler implements AsyncMethodCallback<startElection_call> {

  private static final Logger logger = LoggerFactory.getLogger(ElectionHandler.class);

  private RaftServer raftServer;
  private Node voter;
  private long currTerm;
  private AtomicInteger quorum;
  private AtomicBoolean terminated;
  private AtomicBoolean electionValid;

  public ElectionHandler(RaftServer raftServer, Node voter, long currTerm, AtomicInteger quorum,
      AtomicBoolean terminated, AtomicBoolean electionValid) {
    this.raftServer = raftServer;
    this.voter = voter;
    this.currTerm = currTerm;
    this.quorum = quorum;
    this.terminated = terminated;
    this.electionValid = electionValid;
  }

  @Override
  public void onComplete(startElection_call resp) {
    logger.info("Received an election response");
    long voterTerm;
    try {
      voterTerm = resp.getResult();
    } catch (TException e) {
      onError(e);
      return;
    }

    logger.info("Election response term {}", voterTerm);
    synchronized (raftServer.getTerm()) {
      if (terminated.get()) {
        // a voter has rejected this election, which means the term or the log id falls behind
        // this node is not able to be the leader
        return;
      }

      if (voterTerm == RaftServer.RESPONSE_AGREE) {
        long remaining = quorum.decrementAndGet();
        logger.info("Received a for vote, reaming votes to succeed: {}", remaining);
        if (remaining == 0) {
          // the election is valid
          electionValid.set(true);
          terminated.set(true);
          raftServer.getTerm().notifyAll();
        }
        // still need more votes
      } else {
        // the election is rejected
        terminated.set(true);
        if (voterTerm < currTerm) {
          // the rejection from a node with a smaller term means the log of this node falls behind
          logger.info("Election {} rejected: The node has stale logs", currTerm);
        } else {
          // the election is rejected by a node with a bigger term, update current term to it
          raftServer.getTerm().set(voterTerm);
          logger.info("Election {} rejected: The term of this node is no bigger than {}",
              voterTerm, currTerm);
        }
        raftServer.getTerm().notifyAll();
      }
    }
  }

  @Override
  public void onError(Exception exception) {
    logger.warn("A voter {} encountered an error:", voter, exception);
  }
}