/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.netty.connectionpool;

import com.google.common.base.Strings;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerStats;
import com.netflix.niws.loadbalancer.DiscoveryEnabledServer;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Timer;
import com.netflix.zuul.exception.OutboundErrorType;
import com.netflix.zuul.passport.CurrentPassport;
import com.netflix.zuul.passport.PassportState;
import com.netflix.zuul.stats.Timing;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User: michaels@netflix.com
 * Date: 7/8/16
 * Time: 1:09 PM
 */
public class PerServerConnectionPool implements IConnectionPool
{
    private ConcurrentHashMap<EventLoop, Deque<PooledConnection>> connectionsPerEventLoop = new ConcurrentHashMap<>();

    private final Server server;
    private final ServerStats stats;
    private final InstanceInfo instanceInfo;
    private final NettyClientConnectionFactory connectionFactory;
    private final PooledConnectionFactory pooledConnectionFactory;
    private final ConnectionPoolConfig config;
    private final IClientConfig niwsClientConfig;


    private final Counter createNewConnCounter;
    private final Counter createConnSucceededCounter;
    private final Counter createConnFailedCounter;
    
    private final Counter requestConnCounter;
    private final Counter reuseConnCounter;
    private final Counter connTakenFromPoolIsNotOpen;
    private final Counter maxConnsPerHostExceededCounter;
    private final Timer connEstablishTimer;
    private final AtomicInteger connsInPool;
    private final AtomicInteger connsInUse;

    /** 
     * This is the count of connections currently in progress of being established. 
     * They will only be added to connsInUse _after_ establishment has completed.这是当前正在建立的连接数。他们只会在建厂完成后才会加入康城。
     */
    private final AtomicInteger connCreationsInProgress;

    private static final Logger LOG = LoggerFactory.getLogger(PerServerConnectionPool.class);


    public PerServerConnectionPool(Server server, ServerStats stats, InstanceInfo instanceInfo,
                                   NettyClientConnectionFactory connectionFactory,
                                   PooledConnectionFactory pooledConnectionFactory,
                                   ConnectionPoolConfig config,
                                   IClientConfig niwsClientConfig,
                                   Counter createNewConnCounter, 
                                   Counter createConnSucceededCounter, 
                                   Counter createConnFailedCounter,
                                   Counter requestConnCounter, Counter reuseConnCounter, 
                                   Counter connTakenFromPoolIsNotOpen,
                                   Counter maxConnsPerHostExceededCounter,
                                   Timer connEstablishTimer,
                                   AtomicInteger connsInPool, AtomicInteger connsInUse)
    {
        this.server = server;
        this.stats = stats;
        this.instanceInfo = instanceInfo;
        this.connectionFactory = connectionFactory;
        this.pooledConnectionFactory = pooledConnectionFactory;
        this.config = config;
        this.niwsClientConfig = niwsClientConfig;
        this.createNewConnCounter = createNewConnCounter;
        this.createConnSucceededCounter = createConnSucceededCounter;
        this.createConnFailedCounter = createConnFailedCounter;
        this.requestConnCounter = requestConnCounter;
        this.reuseConnCounter = reuseConnCounter;
        this.connTakenFromPoolIsNotOpen = connTakenFromPoolIsNotOpen;
        this.maxConnsPerHostExceededCounter = maxConnsPerHostExceededCounter;
        this.connEstablishTimer = connEstablishTimer;
        this.connsInPool = connsInPool;
        this.connsInUse = connsInUse;
        
        this.connCreationsInProgress = new AtomicInteger(0);
    }

    @Override
    public ConnectionPoolConfig getConfig()
    {
        return this.config;
    }

    public IClientConfig getNiwsClientConfig()
    {
        return niwsClientConfig;
    }

    public Server getServer()
    {
        return server;
    }

    @Override
    public boolean isAvailable()
    {
        return true;
    }


    /** function to run when a connection is acquired before returning it to caller.函数，用于在将连接返回给调用方之前获取连接时运行。 */
    private void onAcquire(final PooledConnection conn, String httpMethod, String uriStr, 
                           int attemptNum, CurrentPassport passport)
    {
        passport.setOnChannel(conn.getChannel());
        removeIdleStateHandler(conn);

        conn.setInUse();
        if (LOG.isDebugEnabled()) LOG.debug("PooledConnection acquired: " + conn.toString());
    }

    protected void removeIdleStateHandler(PooledConnection conn) {
        DefaultClientChannelManager.removeHandlerFromPipeline(DefaultClientChannelManager.IDLE_STATE_HANDLER_NAME, conn.getChannel().pipeline());
    }

    @Override
    public Promise<PooledConnection> acquire(EventLoop eventLoop, Object key, String httpMethod, String uri,
                                             int attemptNum, CurrentPassport passport,
                                             AtomicReference<String> selectedHostAddr)
    {
        requestConnCounter.increment();
        stats.incrementActiveRequestsCount();
        
        Promise<PooledConnection> promise = eventLoop.newPromise();

        // Try getting a connection from the pool.尝试从池中获得连接。
        final PooledConnection conn = tryGettingFromConnectionPool(eventLoop);
        if (conn != null) {
            // There was a pooled connection available, so use this one.有一个可用的池连接，所以使用这个。
            conn.startRequestTimer();
            conn.incrementUsageCount();
            conn.getChannel().read();
            onAcquire(conn, httpMethod, uri, attemptNum, passport);
            initPooledConnection(conn, promise);
            selectedHostAddr.set(getHostFromServer(conn.getServer()));
        }
        else {
            // connection pool empty, create new connection using client connection factory.连接池为空，使用客户端连接工厂创建新连接。
            tryMakingNewConnection(eventLoop, promise, httpMethod, uri, attemptNum, passport, selectedHostAddr);
        }

        return promise;
    }

    public PooledConnection tryGettingFromConnectionPool(EventLoop eventLoop)
    {
        PooledConnection conn;
        Deque<PooledConnection> connections = getPoolForEventLoop(eventLoop);
        while ((conn = connections.poll()) != null) {

            conn.setInPool(false);

            /* Check that the connection is still open. */
            if (isValidFromPool(conn)) {
                reuseConnCounter.increment();
                connsInUse.incrementAndGet();
                connsInPool.decrementAndGet();
                return conn;
            }
            else {
                connTakenFromPoolIsNotOpen.increment();
                connsInPool.decrementAndGet();
                conn.close();
            }
        }
        return null;
    }

    protected boolean isValidFromPool(PooledConnection conn) {
        return conn.isActive() && conn.getChannel().isOpen();
    }

    protected void initPooledConnection(PooledConnection conn, Promise<PooledConnection> promise) {
        // add custom init code by overriding this method通过覆盖此方法添加自定义init代码
        promise.setSuccess(conn);
    }

    protected Deque<PooledConnection> getPoolForEventLoop(EventLoop eventLoop)
    {
        // We don't want to block under any circumstances, so can't use CHM.computeIfAbsent().
        // Instead we accept the slight inefficiency of an unnecessary instantiation of a ConcurrentLinkedDeque.//我们不想在任何情况下阻塞，所以不能使用CHM.computeIfAbsent()。
//        相反，我们接受了ConcurrentLinkedDeque不必要实例化的低效率。

        Deque<PooledConnection> pool = connectionsPerEventLoop.get(eventLoop);
        if (pool == null) {
            pool = new ConcurrentLinkedDeque<>();
            connectionsPerEventLoop.putIfAbsent(eventLoop, pool);
        }
        return pool;
    }

    protected void tryMakingNewConnection(final EventLoop eventLoop, final Promise<PooledConnection> promise,
                                        final String httpMethod, final String uri, final int attemptNum,
                                        final CurrentPassport passport, final AtomicReference<String> selectedHostAddr)
    {
        // Enforce MaxConnectionsPerHost config.
        int maxConnectionsPerHost = config.maxConnectionsPerHost();
        int openAndOpeningConnectionCount = stats.getOpenConnectionsCount() + connCreationsInProgress.get(); 
        if (maxConnectionsPerHost != -1 && openAndOpeningConnectionCount >= maxConnectionsPerHost) {
            maxConnsPerHostExceededCounter.increment();
            promise.setFailure(new OriginConnectException(
                "maxConnectionsPerHost=" + maxConnectionsPerHost + ", connectionsPerHost=" + openAndOpeningConnectionCount,
                    OutboundErrorType.ORIGIN_SERVER_MAX_CONNS));
            LOG.warn("Unable to create new connection because at MaxConnectionsPerHost! "
                            + "maxConnectionsPerHost=" + maxConnectionsPerHost
                            + ", connectionsPerHost=" + openAndOpeningConnectionCount
                            + ", host=" + instanceInfo.getId()
                            + "origin=" + config.getOriginName()
                    );
            return;
        }

        Timing timing = startConnEstablishTimer();
        try {
            createNewConnCounter.increment();
            connCreationsInProgress.incrementAndGet();
            passport.add(PassportState.ORIGIN_CH_CONNECTING);
            
            // Choose to use either IP or hostname.
            String host = getHostFromServer(server);
            selectedHostAddr.set(host);

//            连接netty server
            final ChannelFuture cf = connectToServer(eventLoop, passport, host);

            if (cf.isDone()) {
                endConnEstablishTimer(timing);
                handleConnectCompletion(cf, promise, httpMethod, uri, attemptNum, passport);
            }
            else {
                cf.addListener(future -> {
                    try {
                        endConnEstablishTimer(timing);
                        handleConnectCompletion((ChannelFuture) future, promise, httpMethod, uri, attemptNum, passport);
                    }
                    catch (Throwable e) {
                        if (! promise.isDone()) {
                            promise.setFailure(e);
                        }
                        LOG.warn("Error creating new connection! "
                                        + "origin=" + config.getOriginName()
                                        + ", host=" + instanceInfo.getId()
                                );
                    }
                });
            }
        }
        catch (Throwable e) {
            endConnEstablishTimer(timing);
            promise.setFailure(e);
        }
    }

    protected ChannelFuture connectToServer(EventLoop eventLoop, CurrentPassport passport, String host) {
        return connectionFactory.connect(eventLoop, host, server.getPort(), passport);
    }

    private Timing startConnEstablishTimer()
    {
        Timing timing = new Timing("connection_establish");
        timing.start();
        return timing;
    }

    private void endConnEstablishTimer(Timing timing)
    {
        timing.end();
        connEstablishTimer.record(timing.getDuration(), TimeUnit.NANOSECONDS);
    }

    protected String getHostFromServer(Server server)
    {
        String host = server.getHost();
        if (! config.useIPAddrForServer()) {
            return host;
        }
        if (server instanceof DiscoveryEnabledServer) {
            DiscoveryEnabledServer discoveryServer = (DiscoveryEnabledServer) server;
            if (discoveryServer.getInstanceInfo() != null) {
                String ip = discoveryServer.getInstanceInfo().getIPAddr();
                if (! Strings.isNullOrEmpty(ip)) {
                    host = ip;
                }
            }
        }
        return host;
    }

    protected void handleConnectCompletion(final ChannelFuture cf,
                                           final Promise<PooledConnection> callerPromise,
                                           final String httpMethod,
                                           final String uri,
                                           final int attemptNum,
                                           final CurrentPassport passport)
    {
        connCreationsInProgress.decrementAndGet();
        
        if (cf.isSuccess()) {
            
            passport.add(PassportState.ORIGIN_CH_CONNECTED);
            
            stats.incrementOpenConnectionsCount();
            createConnSucceededCounter.increment();
            connsInUse.incrementAndGet();

//            创建连接
            createConnection(cf, callerPromise, httpMethod, uri, attemptNum, passport);
        }
        else {
            stats.incrementSuccessiveConnectionFailureCount();
            stats.addToFailureCount();
            stats.decrementActiveRequestsCount();
            createConnFailedCounter.increment();
            callerPromise.setFailure(new OriginConnectException(cf.cause().getMessage(), OutboundErrorType.CONNECT_ERROR));
        }
    }

    protected void createConnection(ChannelFuture cf, Promise<PooledConnection> callerPromise, String httpMethod, String uri,
                                    int attemptNum, CurrentPassport passport) {
        final PooledConnection conn = pooledConnectionFactory.create(cf.channel());

        conn.incrementUsageCount();
        conn.startRequestTimer();
        conn.getChannel().read();
        onAcquire(conn, httpMethod, uri, attemptNum, passport);
        callerPromise.setSuccess(conn);
    }

    @Override
    {
    public boolean release(PooledConnection conn)
        if (conn == null) {
            return false;
        }
        if (conn.isInPool()) {
            return false;
        }

        // Get the eventloop for this channel.
        EventLoop eventLoop = conn.getChannel().eventLoop();
        Deque<PooledConnection> connections = getPoolForEventLoop(eventLoop);
        
        CurrentPassport passport = CurrentPassport.fromChannel(conn.getChannel());

        // Discard conn if already at least above waterline in the pool already for this server.如果这个服务器已经在池中的水线以上，则丢弃conn。
        int poolWaterline = config.perServerWaterline();
        if (poolWaterline > -1 && connections.size() >= poolWaterline) {
            conn.close();
            conn.setInPool(false);
            return false;
        }
        // Attempt to return connection to the pool.尝试返回到池的连接。
        else if (connections.offer(conn)) {
            conn.setInPool(true);
            connsInPool.incrementAndGet();
            passport.add(PassportState.ORIGIN_CH_POOL_RETURNED);
            return true;
        }
        else {
            // If the pool is full, then close the conn and discard.如果池已满，则关闭连接并丢弃。
            conn.close();
            conn.setInPool(false);
            return false;
        }
    }

    @Override
    public boolean remove(PooledConnection conn)
    {
        if (conn == null) {
            return false;
        }
        if (! conn.isInPool()) {
            return false;
        }

        // Get the eventloop for this channel.获取此通道的eventloop。
        EventLoop eventLoop = conn.getChannel().eventLoop();

        // Attempt to return connection to the pool.尝试返回到池的连接。
        Deque<PooledConnection> connections = getPoolForEventLoop(eventLoop);
        if (connections.remove(conn)) {
            conn.setInPool(false);
            connsInPool.decrementAndGet();
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public void shutdown()
    {
        for (Deque<PooledConnection> connections : connectionsPerEventLoop.values()) {
            for (PooledConnection conn : connections) {
                conn.close();
            }
        }
    }

    @Override
    public int getConnsInPool() {
        return connsInPool.get();
    }

    @Override
    public int getConnsInUse() {
        return connsInUse.get();
    }

}
