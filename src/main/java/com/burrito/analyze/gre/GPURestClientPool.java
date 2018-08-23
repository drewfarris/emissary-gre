package com.burrito.analyze.gre;

import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class GPURestClientPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GPURestClientPool.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition freeCondition = this.lock.newCondition();

    private final String host;
    private final int port;
    private final HttpMethod method;
    private final String path;
    private final int max;

    private final Queue<GPURestClient> free = new LinkedList<>();

    private int created;
    private GPURestClient[] allchannels;

    /**
     *
     * @param host
     * @param port
     * @param method
     * @param path
     * @param max
     */
    public GPURestClientPool(String host, int port, HttpMethod method, String path, int max) {
        this.host = host;
        this.port = port;
        this.method = method;
        this.path = path;
        this.max = max;
        this.allchannels = new GPURestClient[max];
    }

    public int getFreeSize() {
        return this.free.size();
    }

    public int getCreatedCount() {
        return this.created;
    }

    public GPURestClient getFree() throws InterruptedException, IOException {
        this.lock.lock();
        GPURestClient client = null;
        try {
            checkClosed();
            client = findFree();
            return client;
        } catch (Throwable t) {
            if (client != null) {
                logger.debug("Throwable occurred while obtaining channel. Returning to the pool. {}", client, t);
                free(client);
            }
            throw t;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Flushes underlying channel and writes journal entry, updating current position.
     *
     * @param client the GPURestClient to flush
     */
    void free(final GPURestClient client) {
        if (client == null) {
            throw new IllegalArgumentException("Cannot return a null GPURestClient.");
        }
        this.lock.lock();
        try {
            if (this.free.contains(client) || !this.free.offer(client)) {
                logger.warn("Could not return the channel to the pool {}", this);
            }
            // signal everyone since close and find may be waiting
            this.freeCondition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Closes the underlying pool. This method will block if any resources have not been returned.
     *
     * @throws InterruptedException If interrupted.
     */
    @Override
    public void close() throws InterruptedException {
        this.lock.lock();
        try {
            while (this.free.size() < this.created) {
                logger.debug("Waiting for leased {} objects.", this.created - this.free.size());
                this.freeCondition.await();
            }
            for (final GPURestClient fc : this.free) {
                for (GPURestClient tc : this.allchannels) {
                    if (tc == fc) {
                        fc.close();
                    }
                }
            }
            this.allchannels = null;
        } finally {
            this.lock.unlock();
        }
    }

    private void checkClosed() throws ClosedChannelException {
        if (this.allchannels == null) {
            throw new ClosedChannelException();
        }
    }

    private GPURestClient findFree() throws InterruptedException, IOException {
        while (this.free.isEmpty()) {
            if (this.created < this.max) {
                this.createClient();
            } else {
                this.freeCondition.await();
                checkClosed();
            }
        }
        return this.free.poll();
    }

    private void createClient() throws InterruptedException {
        final GPURestClient client = new GPURestClient(host, port, method, path);
        client.init();
        this.allchannels[this.created++] = client;
        this.free.add(client);
    }
}
