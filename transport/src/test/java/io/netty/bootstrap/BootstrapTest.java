/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

package io.netty.bootstrap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.SystemPropertyUtil;
import org.junit.AfterClass;
import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class BootstrapTest {

    private static final EventLoopGroup groupA = new DefaultEventLoopGroup(1);
    private static final EventLoopGroup groupB = new DefaultEventLoopGroup(1);
    private static final ChannelInboundHandler dummyHandler = new DummyHandler();

    public static void main(String[] args){

    }
    @Test
    public void testCreateEventLoopGroup() throws Exception{
        //创建两个线程组bossGroup和workerGroup, 含有的子线程NioEventLoop的个数默认为cpu核数的两倍
        // bossGroup只是处理连接请求 ,真正的和客户端业务处理，会交给workerGroup完成
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            //创建服务器端的启动对象
            ServerBootstrap bootstrap = new ServerBootstrap();
            //使用链式编程来配置参数
            //设置两个线程组
            bootstrap.group(bossGroup, workerGroup)
                    //使用NioServerSocketChannel作为服务器的通道实现
                    .channel(NioServerSocketChannel.class)
                    // 初始化服务器连接队列大小，服务端处理客户端连接请求是顺序处理的,所以同一时间只能处理一个客户端连接。
                    // 多个客户端同时来的时候,服务端将不能处理的客户端连接请求放在队列中等待处理
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    //处理器
                    //创建通道初始化对象，设置初始化参数
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            //对workerGroup的SocketChannel设置处理器
                            ch.pipeline().addLast(new NettyServerHandler());
                        }
                    });
            System.out.println("netty server start。。");
            //绑定一个端口并且同步, 生成了一个ChannelFuture异步对象，通过isDone()等方法可以判断异步事件的执行情况
            //启动服务器(并绑定端口)，bind是异步操作，sync方法是等待异步操作执行完毕
            ChannelFuture cf = bootstrap.bind(9000).sync();
            //给cf注册监听器，监听我们关心的事件
            /*cf.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (cf.isSuccess()) {
                        System.out.println("监听端口9000成功");
                    } else {
                        System.out.println("监听端口9000失败");
                    }
                }
            });*/
            //对通道关闭进行监听，closeFuture是异步操作，监听通道关闭
            // 通过sync方法同步等待通道关闭处理完毕，这里会阻塞等待通道关闭完成
            cf.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    @Test
    public void testByteBuffer(){
        ByteBuf buffer = Unpooled.buffer(20);
        for (int i = 0; i < 5; i++) {
            buffer.writeBytes("测试一下扩容".getBytes());
        }
    }

    /**
     * 默认线程数
     */
    @Test
    public void testDefaultThreadNumbers(){
        System.err.println(Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2)));
    }
    @AfterClass
    public static void destroy() {
        groupA.shutdownGracefully();
        groupB.shutdownGracefully();
        groupA.terminationFuture().syncUninterruptibly();
        groupB.terminationFuture().syncUninterruptibly();
    }

    @Test(timeout = 10000)
    public void testBindDeadLock() throws Exception {
        final Bootstrap bootstrapA = new Bootstrap();
        bootstrapA.group(groupA);
        bootstrapA.channel(LocalChannel.class);
        bootstrapA.handler(dummyHandler);

        final Bootstrap bootstrapB = new Bootstrap();
        bootstrapB.group(groupB);
        bootstrapB.channel(LocalChannel.class);
        bootstrapB.handler(dummyHandler);

        List<Future<?>> bindFutures = new ArrayList<Future<?>>();

        // Try to bind from each other.
        for (int i = 0; i < 1024; i ++) {
            bindFutures.add(groupA.next().submit(new Runnable() {
                @Override
                public void run() {
                    bootstrapB.bind(LocalAddress.ANY);
                }
            }));

            bindFutures.add(groupB.next().submit(new Runnable() {
                @Override
                public void run() {
                    bootstrapA.bind(LocalAddress.ANY);
                }
            }));
        }

        for (Future<?> f: bindFutures) {
            f.sync();
        }
    }

    @Test(timeout = 10000)
    public void testConnectDeadLock() throws Exception {
        final Bootstrap bootstrapA = new Bootstrap();
        bootstrapA.group(groupA);
        bootstrapA.channel(LocalChannel.class);
        bootstrapA.handler(dummyHandler);

        final Bootstrap bootstrapB = new Bootstrap();
        bootstrapB.group(groupB);
        bootstrapB.channel(LocalChannel.class);
        bootstrapB.handler(dummyHandler);

        List<Future<?>> bindFutures = new ArrayList<Future<?>>();

        // Try to connect from each other.
        for (int i = 0; i < 1024; i ++) {
            bindFutures.add(groupA.next().submit(new Runnable() {
                @Override
                public void run() {
                    bootstrapB.connect(LocalAddress.ANY);
                }
            }));

            bindFutures.add(groupB.next().submit(new Runnable() {
                @Override
                public void run() {
                    bootstrapA.connect(LocalAddress.ANY);
                }
            }));
        }

        for (Future<?> f: bindFutures) {
            f.sync();
        }
    }

    @Test
    public void testLateRegisterSuccess() throws Exception {
        TestEventLoopGroup group = new TestEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(group);
            bootstrap.channel(LocalServerChannel.class);
            bootstrap.childHandler(new DummyHandler());
            bootstrap.localAddress(new LocalAddress("1"));
            ChannelFuture future = bootstrap.bind();
            assertFalse(future.isDone());
            group.promise.setSuccess();
            final BlockingQueue<Boolean> queue = new LinkedBlockingQueue<Boolean>();
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    queue.add(future.channel().eventLoop().inEventLoop(Thread.currentThread()));
                    queue.add(future.isSuccess());
                }
            });
            assertTrue(queue.take());
            assertTrue(queue.take());
        } finally {
            group.shutdownGracefully();
            group.terminationFuture().sync();
        }
    }

    @Test
    public void testLateRegisterSuccessBindFailed() throws Exception {
        TestEventLoopGroup group = new TestEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(group);
            bootstrap.channelFactory(new ChannelFactory<ServerChannel>() {
                @Override
                public ServerChannel newChannel() {
                    return new LocalServerChannel() {
                        @Override
                        public ChannelFuture bind(SocketAddress localAddress) {
                            // Close the Channel to emulate what NIO and others impl do on bind failure
                            // See https://github.com/netty/netty/issues/2586
                            close();
                            return newFailedFuture(new SocketException());
                        }

                        @Override
                        public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
                            // Close the Channel to emulate what NIO and others impl do on bind failure
                            // See https://github.com/netty/netty/issues/2586
                            close();
                            return promise.setFailure(new SocketException());
                        }
                    };
                }
            });
            bootstrap.childHandler(new DummyHandler());
            bootstrap.localAddress(new LocalAddress("1"));
            ChannelFuture future = bootstrap.bind();
            assertFalse(future.isDone());
            group.promise.setSuccess();
            final BlockingQueue<Boolean> queue = new LinkedBlockingQueue<Boolean>();
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    queue.add(future.channel().eventLoop().inEventLoop(Thread.currentThread()));
                    queue.add(future.isSuccess());
                }
            });
            assertTrue(queue.take());
            assertFalse(queue.take());
        } finally {
            group.shutdownGracefully();
            group.terminationFuture().sync();
        }
    }

    @Test(expected = ConnectException.class, timeout = 10000)
    public void testLateRegistrationConnect() throws Exception {
        EventLoopGroup group = new DelayedEventLoopGroup();
        try {
            final Bootstrap bootstrapA = new Bootstrap();
            bootstrapA.group(group);
            bootstrapA.channel(LocalChannel.class);
            bootstrapA.handler(dummyHandler);
            bootstrapA.connect(LocalAddress.ANY).syncUninterruptibly();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void testAsyncResolutionSuccess() throws Exception {
        final Bootstrap bootstrapA = new Bootstrap();
        bootstrapA.group(groupA);
        bootstrapA.channel(LocalChannel.class);
        bootstrapA.resolver(new TestAddressResolverGroup(true));
        bootstrapA.handler(dummyHandler);

        final ServerBootstrap bootstrapB = new ServerBootstrap();
        bootstrapB.group(groupB);
        bootstrapB.channel(LocalServerChannel.class);
        bootstrapB.childHandler(dummyHandler);
        SocketAddress localAddress = bootstrapB.bind(LocalAddress.ANY).sync().channel().localAddress();

        // Connect to the server using the asynchronous resolver.
        bootstrapA.connect(localAddress).sync();
    }

    @Test
    public void testAsyncResolutionFailure() throws Exception {
        final Bootstrap bootstrapA = new Bootstrap();
        bootstrapA.group(groupA);
        bootstrapA.channel(LocalChannel.class);
        bootstrapA.resolver(new TestAddressResolverGroup(false));
        bootstrapA.handler(dummyHandler);

        final ServerBootstrap bootstrapB = new ServerBootstrap();
        bootstrapB.group(groupB);
        bootstrapB.channel(LocalServerChannel.class);
        bootstrapB.childHandler(dummyHandler);
        SocketAddress localAddress = bootstrapB.bind(LocalAddress.ANY).sync().channel().localAddress();

        // Connect to the server using the asynchronous resolver.
        ChannelFuture connectFuture = bootstrapA.connect(localAddress);

        // Should fail with the UnknownHostException.
        assertThat(connectFuture.await(10000), is(true));
        assertThat(connectFuture.cause(), is(instanceOf(UnknownHostException.class)));
        assertThat(connectFuture.channel().isOpen(), is(false));
    }

    @Test
    public void testGetResolverFailed() throws Exception {
        class TestException extends RuntimeException { }

        final Bootstrap bootstrapA = new Bootstrap();
        bootstrapA.group(groupA);
        bootstrapA.channel(LocalChannel.class);

        bootstrapA.resolver(new AddressResolverGroup<SocketAddress>() {
            @Override
            protected AddressResolver<SocketAddress> newResolver(EventExecutor executor) {
                throw new TestException();
            }
        });
        bootstrapA.handler(dummyHandler);

        final ServerBootstrap bootstrapB = new ServerBootstrap();
        bootstrapB.group(groupB);
        bootstrapB.channel(LocalServerChannel.class);
        bootstrapB.childHandler(dummyHandler);
        SocketAddress localAddress = bootstrapB.bind(LocalAddress.ANY).sync().channel().localAddress();

        // Connect to the server using the asynchronous resolver.
        ChannelFuture connectFuture = bootstrapA.connect(localAddress);

        // Should fail with the IllegalStateException.
        assertThat(connectFuture.await(10000), is(true));
        assertThat(connectFuture.cause(), instanceOf(IllegalStateException.class));
        assertThat(connectFuture.cause().getCause(), instanceOf(TestException.class));
        assertThat(connectFuture.channel().isOpen(), is(false));
    }

    @Test
    public void testChannelFactoryFailureNotifiesPromise() throws Exception {
        final RuntimeException exception = new RuntimeException("newChannel crash");

        final Bootstrap bootstrap = new Bootstrap()
                .handler(dummyHandler)
                .group(groupA)
                .channelFactory(new ChannelFactory<Channel>() {
            @Override
            public Channel newChannel() {
                throw exception;
            }
        });

        ChannelFuture connectFuture = bootstrap.connect(LocalAddress.ANY);

        // Should fail with the RuntimeException.
        assertThat(connectFuture.await(10000), is(true));
        assertThat(connectFuture.cause(), sameInstance((Throwable) exception));
        assertThat(connectFuture.channel(), is(not(nullValue())));
    }

    @Test
    public void testChannelOptionOrderPreserve() throws InterruptedException {
        final BlockingQueue<ChannelOption<?>> options = new LinkedBlockingQueue<ChannelOption<?>>();
        class ChannelConfigValidator extends DefaultChannelConfig {
            ChannelConfigValidator(Channel channel) {
                super(channel);
            }

            @Override
            public <T> boolean setOption(ChannelOption<T> option, T value) {
                options.add(option);
                return super.setOption(option, value);
            }
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Bootstrap bootstrap = new Bootstrap()
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        latch.countDown();
                    }
                })
                .group(groupA)
                .channelFactory(new ChannelFactory<Channel>() {
                    @Override
                    public Channel newChannel() {
                        return new LocalChannel() {
                            private ChannelConfigValidator config;
                            @Override
                            public synchronized ChannelConfig config() {
                                if (config == null) {
                                    config = new ChannelConfigValidator(this);
                                }
                                return config;
                            }
                        };
                    }
                })
                .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 1)
                .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 2);

        bootstrap.register().syncUninterruptibly();

        latch.await();

        // Check the order is the same as what we defined before.
        assertSame(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, options.take());
        assertSame(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, options.take());
    }

    private static final class DelayedEventLoopGroup extends DefaultEventLoop {
        @Override
        public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
            // Delay registration
            execute(new Runnable() {
                @Override
                public void run() {
                    DelayedEventLoopGroup.super.register(channel, promise);
                }
            });
            return promise;
        }
    }

    private static final class TestEventLoopGroup extends DefaultEventLoopGroup {

        ChannelPromise promise;

        TestEventLoopGroup() {
            super(1);
        }

        @Override
        public ChannelFuture register(Channel channel) {
            super.register(channel).syncUninterruptibly();
            promise = channel.newPromise();
            return promise;
        }

        @Override
        public ChannelFuture register(ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture register(Channel channel, final ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }
    }

    @Sharable
    private static final class DummyHandler extends ChannelInboundHandlerAdapter { }

    private static final class TestAddressResolverGroup extends AddressResolverGroup<SocketAddress> {

        private final boolean success;

        TestAddressResolverGroup(boolean success) {
            this.success = success;
        }

        @Override
        protected AddressResolver<SocketAddress> newResolver(EventExecutor executor) throws Exception {
            return new AbstractAddressResolver<SocketAddress>(executor) {

                @Override
                protected boolean doIsResolved(SocketAddress address) {
                    return false;
                }

                @Override
                protected void doResolve(
                        final SocketAddress unresolvedAddress, final Promise<SocketAddress> promise) {
                    executor().execute(new Runnable() {
                        @Override
                        public void run() {
                            if (success) {
                                promise.setSuccess(unresolvedAddress);
                            } else {
                                promise.setFailure(new UnknownHostException(unresolvedAddress.toString()));
                            }
                        }
                    });
                }

                @Override
                protected void doResolveAll(
                        final SocketAddress unresolvedAddress, final Promise<List<SocketAddress>> promise)
                        throws Exception {
                    executor().execute(new Runnable() {
                        @Override
                        public void run() {
                            if (success) {
                                promise.setSuccess(Collections.singletonList(unresolvedAddress));
                            } else {
                                promise.setFailure(new UnknownHostException(unresolvedAddress.toString()));
                            }
                        }
                    });
                }
            };
        }
    }
}
