package de.otto.edison.jobtrigger.trigger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.google.common.collect.ImmutableList;

import de.otto.edison.jobtrigger.Server;
import de.otto.edison.jobtrigger.discovery.DiscoveryService;

@SpringBootTest(classes = {Server.class}, webEnvironment = DEFINED_PORT, properties = {"server.port=0"})
@RunWith(SpringJUnit4ClassRunner.class)
public class TriggerControllerIntegrationTest {

    @Autowired
    private TriggerService triggerService;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    private DiscoveryService discoveryService;

    @Configuration
    @PropertySource("/version.properties")
    static class TestConfig {
        @Bean
        @Primary
        public DiscoveryService discoveryService() {
            return mock(DiscoveryService.class);
        }
    }

    private MockMvc mockMvc;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    public void shouldReturnListOfTriggers() throws Exception {
        when(discoveryService.allJobDefinitions()).thenReturn(ImmutableList.of());

        mockMvc.perform(post("/triggers/start"));
        mockMvc.perform(get("/triggers"));

        verify(discoveryService).allJobDefinitions();
    }
}