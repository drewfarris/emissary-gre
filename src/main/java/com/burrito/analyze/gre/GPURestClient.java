package com.burrito.analyze.gre;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class GPURestClient {

    private final static Logger logger = LoggerFactory.getLogger(GPURestClient.class);
    private final static int MAX_RESPONSE_LENGTH = 100 * 1024 * 1024; // 100M response limit;

    private final String host;
    private final int port;
    private final HttpMethod method;
    private final String path;

    private EventLoopGroup group;
    private Channel channel;

    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<String>();

    public GPURestClient() {
        this("localhost", 8008);
    }

    public GPURestClient(String host, int port) {
        this(host, port, HttpMethod.POST, "/api/classify");
    }

    public GPURestClient(String host, int port, HttpMethod method, String path) {
        this.host = host;
        this.port = port;
        this.method = method;
        this.path = path;
    }

    public void init() throws InterruptedException {
        // TODO: disable this in production
        // ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        final EventLoopGroup group = new NioEventLoopGroup();
        final Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class).handler(new NettyGREClientInitializer(responseQueue));

        this.group = group;

        // Make the connection attempt.
        this.channel = b.connect(host, port).sync().channel();
    }

    public void close() throws InterruptedException {
        if (channel != null && channel.isOpen()) {
            logger.info("Closing channel");
            channel.close();
            channel.closeFuture().sync();
            channel = null;
        }

        logger.info("Closing group");
        group.shutdownGracefully().sync();

        logger.info("Close complete");
    }

    public String process(byte[] bytes) throws InterruptedException {

        // Prepare the HTTP request.
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, Unpooled.wrappedBuffer(bytes));
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        // Send the HTTP request.
        channel.writeAndFlush(request);


        // TODO: fix this this it is really wonky.
        String response;
        boolean interrupted = false;
        for (;;) {
            try {
                response = responseQueue.take();
                break;
            } catch (InterruptedException ignore) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return response;
    }

    public static void main(String[] args) throws Exception {
        byte[] content =
                FileUtils.readFileToByteArray(new File("core/src/test/resources/com/burrito/analyze/gre/GPURestEnginePlaceTest/nouveau.dat"));

        GPURestClient client = new GPURestClient();
        client.init();
        for (int i = 0; i < 100; i++) {
            String result = client.process(content);
            logger.info("result(): {} {}", i, result);
        }
        client.close();

    }

    public static class NettyGREClientInitializer extends ChannelInitializer<SocketChannel> {

        private final BlockingQueue<String> responseQueue;

        public NettyGREClientInitializer(BlockingQueue responseQueue) {
            this.responseQueue = responseQueue;
        }

        @Override
        protected void initChannel(SocketChannel channel) throws Exception {
            final ChannelPipeline p = channel.pipeline();
            p.addLast(new HttpClientCodec());
            p.addLast(new HttpContentDecompressor());
            p.addLast(new HttpObjectAggregator(MAX_RESPONSE_LENGTH));
            p.addLast(new NettyGREResponseHandler(responseQueue));
        }
    }

    public static class NettyGREResponseHandler extends ChannelInboundHandlerAdapter {

        private final BlockingQueue<String> responseQueue;

        public NettyGREResponseHandler(BlockingQueue<String> responseQueue) {
            this.responseQueue = responseQueue;
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                // logger.info("channelRead(): {}", msg.toString());

                if (msg instanceof FullHttpResponse) {
                    final FullHttpResponse response = (FullHttpResponse) msg;
                    // logger.info("STATUS: {}", response.getStatus());
                    // logger.info("VERSION: {}", response.getProtocolVersion());
                    responseQueue.add(response.content().toString(CharsetUtil.UTF_8));
                } else {
                    logger.warn("Unknown message type {}", msg);
                }

            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
