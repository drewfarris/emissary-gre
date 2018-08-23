package com.burrito.analyze.gre;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;

/** Validating Unix Domain Sockets */
public class NettyUnixTest {

    public static void main(String[] args) throws Exception {
        String sockPath = "/tmp/echo.sock";
        final ServerBootstrap bootstrap = new ServerBootstrap();
        EventLoopGroup serverBossEventLoopGroup = new EpollEventLoopGroup();
        EventLoopGroup serverWorkerEventLoopGroup = new EpollEventLoopGroup();
        bootstrap.group(serverBossEventLoopGroup, serverWorkerEventLoopGroup).localAddress(new DomainSocketAddress(sockPath))
                .channel(EpollServerDomainSocketChannel.class).childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(final Channel channel) throws Exception {
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) throws Exception {
                                final ByteBuf buff = ctx.alloc().buffer();
                                buff.writeBytes("This is a test".getBytes());
                                ctx.writeAndFlush(buff).addListeners(new ChannelFutureListener() {
                                    @Override
                                    public void operationComplete(ChannelFuture future) {
                                        future.channel().close();
                                        future.channel().parent().close();
                                    }
                                });
                            }
                        });
                    }
                });
        final ChannelFuture serverFuture = bootstrap.bind().sync();

        final Bootstrap bootstrapClient = new Bootstrap();
        EventLoopGroup clientEventLoop = new EpollEventLoopGroup();
        bootstrapClient.group(clientEventLoop).channel(EpollDomainSocketChannel.class).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(final Channel channel) throws Exception {
                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
                        final ByteBuf buff = (ByteBuf) msg;
                        try {
                            byte[] bytes = new byte[buff.readableBytes()];
                            buff.getBytes(0, bytes);
                            System.out.println(new String(bytes));
                        } finally {
                            buff.release();
                        }
                        ctx.close();
                    }

                    @Override
                    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
                        System.out.println("Error occur when reading from Unix domain socket: " + cause.getMessage());
                        ctx.close();
                    }
                });
            }
        });
        final ChannelFuture clientFuture = bootstrapClient.connect(new DomainSocketAddress(sockPath)).sync();

        clientFuture.channel().closeFuture().sync();
        serverFuture.channel().closeFuture().sync();
        serverBossEventLoopGroup.shutdownGracefully();
        serverWorkerEventLoopGroup.shutdownGracefully();
        clientEventLoop.shutdownGracefully();
    }
}
