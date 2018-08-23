package com.burrito.analyze.gre;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/** Implements the GRE 'protocol' - always returns the same object */
public class TestGPURestEngine implements Runnable {

    private final static Logger logger = LoggerFactory.getLogger(TestGPURestEngine.class);
    private final static int MAX_CONTENT_LENGTH = 500 * 1024 * 1024;

    final int port;
    final ServerBootstrap serverBootstrap;
    final NioEventLoopGroup serverBossGroup;
    final NioEventLoopGroup serverWorkerGroup;

    Channel channel;

    public TestGPURestEngine(int port) throws InterruptedException {
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        serverBootstrap = new ServerBootstrap();
        serverBossGroup = new NioEventLoopGroup();
        serverWorkerGroup = new NioEventLoopGroup();
        serverBootstrap.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class).handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        final ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpServerExpectContinueHandler());
                        p.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                        p.addLast(new HttpMockGPURestEngineHandler());
                    }
                });

        // Bind and start to accept incoming connections.
        channel = serverBootstrap.bind(port).sync().channel();
        SocketAddress address = channel.localAddress();
        logger.info("{} listening on {}", getClass().getName(), channel);
        if (address instanceof InetSocketAddress) {
            this.port = ((InetSocketAddress) address).getPort();
        } else {
            throw new IllegalStateException("SocketAddress is not an InetSocketAddress as expected, was a " + address.getClass());
        }
    }

    @Override
    public void run() {
        try {
            logger.info("Running...");
            channel.closeFuture().sync();
            logger.info("... channel closed");
        } catch (InterruptedException ie) {
            Thread.interrupted();
            logger.info("Interrupted", ie);
        } finally {
            logger.info("Performing cleanup..");
            serverBossGroup.shutdownGracefully();
            serverWorkerGroup.shutdownGracefully();
            channel = null;
            logger.info("...cleanup complete");
        }
    }

    public int getPort() {
        return this.port;
    }

    public void stop() {
        logger.info("Stopping...");
        if (channel != null) {
            channel.close();
        } else {
            throw new IllegalStateException("Server has already been stopped");
        }
    }

    public static class HttpMockGPURestEngineHandler extends ChannelInboundHandlerAdapter {

        private static final String API_URI = "/api/classify";

        private static final String RESPONSE = "[{\"confidence\":0.9982,\"label\":\"n02328150 Angora, Angora rabbit\"},"
                + "{\"confidence\":0.0009,\"label\":\"n02326432 hare\"},"
                + "{\"confidence\":0.0008,\"label\":\"n02325366 wood rabbit, cottontail, cottontail rabbit\"},"
                + "{\"confidence\":0.0001,\"label\":\"n02098286 West Highland white terrier\"},"
                + "{\"confidence\":0.0000,\"label\":\"n02342885 hamster\"}]";

        private static final byte[] CONTENT_RESPONSE = RESPONSE.getBytes(CharsetUtil.UTF_8);
        private static final byte[] NOT_FOUND_RESPONSE = "[{\"message\": \"not found\"}]".getBytes(CharsetUtil.UTF_8);

        private static final AsciiString CONTENT_TYPE = new AsciiString("Content-Type");
        private static final AsciiString CONTENT_LENGTH = new AsciiString("Content-Length");
        private static final AsciiString CONNECTION = new AsciiString("Connection");
        private static final AsciiString KEEP_ALIVE = new AsciiString("keep-alive");

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                logger.info("channelRead(): {}", msg.toString());
                if (msg instanceof FullHttpRequest) {
                    FullHttpRequest req = (FullHttpRequest) msg;
                    if (req.uri().equalsIgnoreCase(API_URI)) {
                        writeSuccess(ctx, req);
                    } else {
                        writeNotFound(ctx, req);
                    }
                } else {
                    logger.warn("Unexpected message type {}", msg);
                }
            } finally {
                // ctx.close();
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }

        protected void writeSuccess(ChannelHandlerContext ctx, FullHttpRequest msg) {
            boolean keepAlive = HttpUtil.isKeepAlive(msg);
            final ByteBuf content = msg.content();

            logger.info("writeSuccess(): {} bytes", content.capacity());
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(CONTENT_RESPONSE));
            response.headers().set(CONTENT_TYPE, "text/plain");
            response.headers().setInt(CONTENT_LENGTH, response.content().readableBytes());

            if (!keepAlive) {
                ctx.write(response).addListener(ChannelFutureListener.CLOSE);
            } else {
                response.headers().set(CONNECTION, KEEP_ALIVE);
                ctx.write(response);
            }
        }

        protected void writeNotFound(ChannelHandlerContext ctx, FullHttpRequest msg) {
            boolean keepAlive = HttpUtil.isKeepAlive(msg);
            final ByteBuf content = msg.content();

            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND, Unpooled.wrappedBuffer(NOT_FOUND_RESPONSE));
            response.headers().set(CONTENT_TYPE, "text/plain");
            response.headers().setInt(CONTENT_LENGTH, response.content().readableBytes());

            if (!keepAlive) {
                ctx.write(response).addListener(ChannelFutureListener.CLOSE);
            } else {
                response.headers().set(CONNECTION, KEEP_ALIVE);
                ctx.write(response);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8008;
        final TestGPURestEngine server = new TestGPURestEngine(port);

        Signal.handle(new Signal("INT"), sig -> {
            logger.info("Stop signal received, shutting down");
            server.stop();
        });

        server.run();
    }
}
