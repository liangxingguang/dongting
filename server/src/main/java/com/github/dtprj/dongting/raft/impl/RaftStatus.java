/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.raft.impl;

import com.github.dtprj.dongting.common.Timestamp;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author huangli
 */
public class RaftStatus {

    // persistent state on all servers
    private int currentTerm;
    private int votedFor;

    // volatile state on all servers
    private long commitIndex;
    private long lastApplied; // shared

    @SuppressWarnings({"unused"})
    private boolean error;
    private boolean installSnapshot;

    private RaftRole role; // shared
    private RaftMember currentLeader; // shared
    private final Timestamp ts = new Timestamp();
    private final int electQuorum;
    private final int rwQuorum;
    private final List<RaftMember> members = new ArrayList<>();
    private final List<RaftMember> observers = new ArrayList<>();

    private PendingMap pendingRequests;
    private long firstCommitIndexOfCurrentTerm;
    private CompletableFuture<Void> firstCommitOfApplied; // shared

    private boolean shareStatusUpdated;
    private volatile ShareStatus shareStatus;
    private long electTimeoutNanos; // shared

    private long leaseStartNanos; // shared

    private long lastElectTime;
    private long heartbeatTime;

    private long lastLogIndex;
    private int lastLogTerm;

    private File statusFile;
    private RandomAccessFile randomAccessStatusFile;
    private FileChannel statusChannel;
    private FileLock statusFileLock;

    private RaftExecutor raftExecutor;
    private boolean saving;

    private static final VarHandle ERROR;

    static {
        try {
            ERROR = MethodHandles.lookup().findVarHandle(RaftStatus.class, "error", boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public RaftStatus(int electQuorum, int rwQuorum, RaftRole initRole) {
        this.electQuorum = electQuorum;
        this.rwQuorum = rwQuorum;
        this.role = initRole;
        lastElectTime = ts.getNanoTime();
        heartbeatTime = ts.getNanoTime();
    }

    public void copyShareStatus() {
        if (shareStatusUpdated) {
            ShareStatus ss = new ShareStatus();
            ss.role = role;
            ss.lastApplied = lastApplied;
            ss.leaseEndNanos = leaseStartNanos + electTimeoutNanos;
            ss.currentLeader = currentLeader;
            ss.firstCommitOfApplied = firstCommitOfApplied;

            this.shareStatusUpdated = false;
            this.shareStatus = ss;
        }
    }

    public void setLastApplied(long lastApplied) {
        this.lastApplied = lastApplied;
        this.shareStatusUpdated = true;
    }

    public void setRole(RaftRole role) {
        this.role = role;
        this.shareStatusUpdated = true;
    }

    public void setLeaseStartNanos(long leaseStartNanos) {
        this.leaseStartNanos = leaseStartNanos;
        this.shareStatusUpdated = true;
    }

    public void setCurrentLeader(RaftMember currentLeader) {
        this.currentLeader = currentLeader;
        this.shareStatusUpdated = true;
    }

    public void setElectTimeoutNanos(long electTimeoutNanos) {
        this.electTimeoutNanos = electTimeoutNanos;
        this.shareStatusUpdated = true;
    }

    public void setFirstCommitOfApplied(CompletableFuture<Void> firstCommitOfApplied) {
        this.firstCommitOfApplied = firstCommitOfApplied;
        this.shareStatusUpdated = true;
    }

    public boolean isError() {
        return (boolean) ERROR.getAcquire(this);
    }

    public void setError(boolean errorState) {
        ERROR.setRelease(this, errorState);
    }

    //------------------------- simple getters and setters--------------------------------

    public int getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(int currentTerm) {
        this.currentTerm = currentTerm;
    }

    public int getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(int votedFor) {
        this.votedFor = votedFor;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public RaftRole getRole() {
        return role;
    }

    public int getElectQuorum() {
        return electQuorum;
    }

    public int getRwQuorum() {
        return rwQuorum;
    }

    public long getHeartbeatTime() {
        return heartbeatTime;
    }

    public void setHeartbeatTime(long heartbeatTime) {
        this.heartbeatTime = heartbeatTime;
    }

    public long getLastElectTime() {
        return lastElectTime;
    }

    public void setLastElectTime(long lastElectTime) {
        this.lastElectTime = lastElectTime;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public void setLastLogIndex(long lastLogIndex) {
        this.lastLogIndex = lastLogIndex;
    }

    public int getLastLogTerm() {
        return lastLogTerm;
    }

    public void setLastLogTerm(int lastLogTerm) {
        this.lastLogTerm = lastLogTerm;
    }

    public List<RaftMember> getMembers() {
        return members;
    }

    public List<RaftMember> getObservers() {
        return observers;
    }

    public PendingMap getPendingRequests() {
        return pendingRequests;
    }

    public void setPendingRequests(PendingMap pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    public long getFirstCommitIndexOfCurrentTerm() {
        return firstCommitIndexOfCurrentTerm;
    }

    public void setFirstCommitIndexOfCurrentTerm(long firstCommitIndexOfCurrentTerm) {
        this.firstCommitIndexOfCurrentTerm = firstCommitIndexOfCurrentTerm;
    }

    public Timestamp getTs() {
        return ts;
    }

    public ShareStatus getShareStatus() {
        return shareStatus;
    }

    public RaftMember getCurrentLeader() {
        return currentLeader;
    }

    public long getElectTimeoutNanos() {
        return electTimeoutNanos;
    }

    public CompletableFuture<Void> getFirstCommitOfApplied() {
        return firstCommitOfApplied;
    }

    public boolean isInstallSnapshot() {
        return installSnapshot;
    }

    public void setInstallSnapshot(boolean installSnapshot) {
        this.installSnapshot = installSnapshot;
    }

    public RandomAccessFile getRandomAccessStatusFile() {
        return randomAccessStatusFile;
    }

    public void setRandomAccessStatusFile(RandomAccessFile randomAccessStatusFile) {
        this.randomAccessStatusFile = randomAccessStatusFile;
    }

    public FileChannel getStatusChannel() {
        return statusChannel;
    }

    public void setStatusChannel(FileChannel statusChannel) {
        this.statusChannel = statusChannel;
    }

    public FileLock getStatusFileLock() {
        return statusFileLock;
    }

    public void setStatusFileLock(FileLock statusFileLock) {
        this.statusFileLock = statusFileLock;
    }

    public RaftExecutor getRaftExecutor() {
        return raftExecutor;
    }

    public void setRaftExecutor(RaftExecutor raftExecutor) {
        this.raftExecutor = raftExecutor;
    }

    public boolean isSaving() {
        return saving;
    }

    public void setSaving(boolean saving) {
        this.saving = saving;
    }

    public File getStatusFile() {
        return statusFile;
    }

    public void setStatusFile(File statusFile) {
        this.statusFile = statusFile;
    }
}
