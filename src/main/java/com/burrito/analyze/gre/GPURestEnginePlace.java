package com.burrito.analyze.gre;

import emissary.analyze.Analyzer;
import emissary.config.Configurator;
import emissary.core.IBaseDataObject;
import emissary.pool.AgentPool;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class GPURestEnginePlace extends Analyzer {

    private static final Logger log = LoggerFactory.getLogger(GPURestEnginePlace.class);

    private static final String DEFAULT_GRE_URI = "http://localhost:8008/api/classify";

    final ThreadLocal<GPURestClient> localClient = new ThreadLocal<>();

    GPURestClientPool clientPool;
    ConcurrentLinkedQueue<GPURestClient> allClients = new ConcurrentLinkedQueue<>();

    public GPURestEnginePlace(final String configInfo, final String dir, final String placeLoc) throws InterruptedException, IOException {
        super(configInfo, dir, placeLoc);
        configurePlace();
    }

    public GPURestEnginePlace() throws InterruptedException, IOException {
        super();
        configurePlace();
    }

    public GPURestEnginePlace(final String configInfo) throws InterruptedException, IOException {
        // the second arg is arbitrary, but needs to look like this,
        // place.machine:port
        super(configInfo, "tcp://bogushost:1234/GPURestEnginePlace");
        configurePlace();
    }

    /**
     * Unit test constructor.
     *
     * @param config the Configurator
     * @throws IOException if an error occurs initializing this Place
     */
    public GPURestEnginePlace(final Configurator config) throws InterruptedException, IOException {
        super();
        configG = config != null ? config : configG;
        configurePlace();
    }

    protected void configurePlace() throws InterruptedException, IOException {
        super.configureAnalyzer();

        try {
            final String greUriString = configG.findStringEntry("GRE_URI", DEFAULT_GRE_URI);
            final int greThreads = configG.findIntEntry("GRE_THREADS", AgentPool.computePoolSize());

            final URI greUri = new URI(greUriString);
            logger.debug("Initializing gre client url {}", greUri);

            clientPool = new GPURestClientPool(greUri.getHost(), greUri.getPort(), HttpMethod.POST, greUri.getRawPath(), greThreads);
            for (int i = 0; i < greThreads; i++) {
                logger.debug("Initializing gre client instance {} of {}", i + 1, greThreads);
                clientPool.free(clientPool.getFree());
            }
        } catch (URISyntaxException ex) {
            throw new RuntimeException("Error configuring GPURestEnginePlace", ex);
        }
    }

    @Override
    public void process(final IBaseDataObject d) {

        try {
            // Assign a GPURestClient to this thread.
            GPURestClient client;
            if ((client = localClient.get()) == null) {
                client = clientPool.getFree();
                localClient.set(client);
                allClients.add(client);
            }

            final byte[] buffer = getPreferredData(d);
            if (null == buffer || buffer.length == 0) {
                return;
            }

            logger.debug("Sending {} bytes to GRE server at {}", buffer.length, "URI");
            final long start = System.nanoTime();
            String result = client.process(buffer);
            final long deltaMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.debug("Recieved response in {} ms", deltaMs);

            d.putParameter("GRE_RESULT", result);
            d.putParameter("GRE_ELAPSED_MSEC", deltaMs);
        } catch (IOException | InterruptedException ex) {
            log.warn("Exception processing object using GREClient: {}", ex.getMessage(), ex);
        }
    }

    @Override
    public void shutDown() {
        try {
            for (GPURestClient client : allClients) {
                clientPool.free(client);
            }
            clientPool.close();
        } catch (InterruptedException ex) {
            log.warn("Interrupted when closing netty client pool");
        }
    }

    public static void main(final String[] argv) {
        mainRunner(GPURestEnginePlace.class.getName(), argv);
    }
}
