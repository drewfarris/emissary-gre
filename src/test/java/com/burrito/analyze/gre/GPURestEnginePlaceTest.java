package com.burrito.analyze.gre;

import emissary.config.Configurator;
import emissary.config.ServiceConfigGuide;
import emissary.place.IServiceProviderPlace;
import emissary.test.core.ExtractionTest;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Collection;

@RunWith(Parameterized.class)
public class GPURestEnginePlaceTest extends ExtractionTest {

    private static TestGPURestEngine testServer;

    @Override
    public void setUpPlace() throws Exception {
        testServer = new TestGPURestEngine(0 /* choose random unused port */);
        // Thread mockServerThread = new Thread(testServer);
        // mockServerThread.setDaemon(true);
        // mockServerThread.start();

        super.setUpPlace();
    }

    @Override
    public void tearDownPlace() {
        super.tearDownPlace();
        testServer.stop();
    }

    public GPURestEnginePlaceTest(final String resource) throws IOException {
        super(resource);
    }

    @Parameterized.Parameters
    public static Collection<?> data() {
        return getMyTestParameterFiles(GPURestEnginePlaceTest.class);
    }

    @Override
    public IServiceProviderPlace createPlace() throws IOException {
        try {
            final int port = testServer.getPort();
            final Configurator config = new ServiceConfigGuide();
            config.addEntry("GRE_URI", "http://localhost:" + port + "/api/classify");
            return new GPURestEnginePlace(config);
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        }
    }
}
