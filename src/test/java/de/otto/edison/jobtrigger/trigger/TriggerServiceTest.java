package de.otto.edison.jobtrigger.trigger;

import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import de.otto.edison.jobtrigger.configuration.JobTriggerProperties;
import de.otto.edison.jobtrigger.definition.JobDefinition;
import de.otto.edison.jobtrigger.definition.JobDefinitionBuilder;
import de.otto.edison.jobtrigger.discovery.DiscoveryService;
import de.otto.edison.jobtrigger.security.BasicAuthCredentials;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static de.otto.edison.jobtrigger.trigger.Triggers.cronTrigger;
import static de.otto.edison.jobtrigger.trigger.Triggers.periodicTrigger;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

@PrepareForTest(TriggerRunnables.class)
@PowerMockIgnore("javax.management.*")
@RunWith(PowerMockRunner.class)
public class TriggerServiceTest {

    Logger log = LoggerFactory.getLogger(TriggerService.class);

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private JobScheduler scheduler;

    @Mock
    private AsyncHttpClient httpClient;

    @Mock
    private BasicAuthCredentials basicAuthCredentials;

    private TriggerService testee;

    @Captor
    ArgumentCaptor<List<JobTrigger>> listArgumentCaptor;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        testee = new TriggerService(discoveryService, scheduler, httpClient, new JobTriggerProperties(), basicAuthCredentials);
        reset(discoveryService, scheduler, httpClient);
        testee.postConstruct();
        PowerMockito.mockStatic(TriggerRunnables.class);
    }

    @Test
    public void shouldStartTriggering() {
        testee.startTriggering();

        assertThat(testee.isStarted(), is(true));
    }

    @Test
    public void shouldNotStopTriggeringIfAlreadyStarted() {
        testee.startTriggering();

        testee.startTriggering();

        verify(scheduler, never()).stopAllTriggers();
    }

    @Test
    public void shouldUpdateTriggersForAllJobDefinitions() {
        final JobDefinition fixedDelayDefinition = new JobDefinitionBuilder().setFixedDelay(Optional.of(Duration.ofDays(2))).createJobDefinition();
        final JobDefinition cronDefinition = new JobDefinitionBuilder().setCron(Optional.of("* * * * * *")).createJobDefinition();
        final JobDefinition noDelayDefinition = new JobDefinitionBuilder().createJobDefinition();
        when(discoveryService.allJobDefinitions()).thenReturn(ImmutableList.of(fixedDelayDefinition, cronDefinition, noDelayDefinition));
        final Runnable expectedRunnable = () -> {
        };
        when(TriggerRunnables.httpTriggerRunnable(eq(httpClient), any(JobDefinition.class), any(TriggerResponseConsumer.class), any(BasicAuthCredentials.class))).thenReturn(expectedRunnable);

        testee.startTriggering();

        verify(scheduler).updateTriggers(listArgumentCaptor.capture());
        assertJobTriggerEquality(listArgumentCaptor.getValue(),
                ImmutableList.of(
                        new JobTrigger(
                                fixedDelayDefinition,
                                periodicTrigger(fixedDelayDefinition.getFixedDelay().get()),
                                expectedRunnable),
                        new JobTrigger(
                                cronDefinition,
                                cronTrigger(cronDefinition.getCron().get()),
                                expectedRunnable)
                ));
    }

    @Test
    public void shouldNotFailIfSingleCronExpressionIsBroken() {
        final JobDefinition fixedDelayDefinition = new JobDefinitionBuilder().setFixedDelay(Optional.of(Duration.ofDays(2))).createJobDefinition();
        final JobDefinition brokenCronDefinition = new JobDefinitionBuilder().setCron(Optional.of("BÄM!")).createJobDefinition();
        final JobDefinition noDelayDefinition = new JobDefinitionBuilder().createJobDefinition();
        when(discoveryService.allJobDefinitions()).thenReturn(ImmutableList.of(fixedDelayDefinition, brokenCronDefinition, noDelayDefinition));
        final Runnable expectedRunnable = () -> {
        };
        when(TriggerRunnables.httpTriggerRunnable(eq(httpClient), any(JobDefinition.class), any(TriggerResponseConsumer.class), any(BasicAuthCredentials.class))).thenReturn(expectedRunnable);

        testee.startTriggering();

        verify(scheduler).updateTriggers(listArgumentCaptor.capture());
        assertThat(listArgumentCaptor.getValue(), hasSize(2));
    }

    @Test
    public void shouldThrowExceptionDuringexecutionIfCronExpressionIsBroken() {
        final JobDefinition brokenCronDefinition = new JobDefinitionBuilder().setCron(Optional.of("BÄM!")).createJobDefinition();
        when(discoveryService.allJobDefinitions()).thenReturn(ImmutableList.of(brokenCronDefinition));

        testee.startTriggering();
        verify(scheduler).updateTriggers(listArgumentCaptor.capture());
        listArgumentCaptor.getValue().get(0).getRunnable().run();
        assertThat(testee.getLastResults().get(0).failed(), is(true));
    }

    @Test
    public void shouldAddSuccessfulResponseAsLastResult() throws Exception {
        final Response responseMock = mock(Response.class);
        final JobDefinition jobDefinition = mock(JobDefinition.class);
        final TriggerService.DefaultTriggerResponseConsumer responseConsumer = testee.new DefaultTriggerResponseConsumer(jobDefinition);

        when(responseMock.getStatusCode()).thenReturn(200);

        responseConsumer.consume(responseMock);

        final TriggerResult triggerResult = testee.getLastResults().get(0);
        assertThat(testee.getLastResults(), hasSize(1));
        assertThat(triggerResult.getJobDefinition(), is(jobDefinition));
        assertThat(triggerResult.getTriggerStatus().getState(), is(TriggerStatus.State.OK));
    }

    @Test
    public void shouldRemoveLastResultsIfMoreThanMaxResults() throws Exception {
        for (int i = 0; i < testee.getMaxJobResults() + 10; i++) {
            final Response responseMock = mock(Response.class);
            final JobDefinition jobDefinition = mock(JobDefinition.class);
            final TriggerService.DefaultTriggerResponseConsumer responseConsumer = testee.new DefaultTriggerResponseConsumer(jobDefinition);

            when(responseMock.getStatusCode()).thenReturn(200);

            responseConsumer.consume(responseMock);
        }

        assertThat(testee.getLastResults(), hasSize(testee.getMaxJobResults()));
    }

    @Test
    public void shouldAddThrowableAsLastResult() throws Exception {
        final Exception throwable = new Exception("some message");
        final JobDefinition jobDefinition = mock(JobDefinition.class);
        final TriggerService.DefaultTriggerResponseConsumer responseConsumer = testee.new DefaultTriggerResponseConsumer(jobDefinition);

        responseConsumer.consume(throwable);

        final TriggerResult triggerResult = testee.getLastResults().get(0);
        assertThat(testee.getLastResults(), hasSize(1));
        assertThat(triggerResult.getJobDefinition(), is(jobDefinition));
        assertThat(triggerResult.getTriggerStatus().getMessage(), is("some message"));
        assertThat(triggerResult.getTriggerStatus().getState(), is(TriggerStatus.State.FAILED));
    }

    @Test
    public void shouldAddConnectExceptionAsLastResult() throws Exception {
        final Exception throwable = new ConnectException("some connect exception");
        final JobDefinition jobDefinition = mock(JobDefinition.class);
        final TriggerService.DefaultTriggerResponseConsumer responseConsumer = testee.new DefaultTriggerResponseConsumer(jobDefinition);


        responseConsumer.consume(throwable);

        final TriggerResult triggerResult = testee.getLastResults().get(0);
        assertThat(testee.getLastResults(), hasSize(1));
        assertThat(triggerResult.getJobDefinition(), is(jobDefinition));
        assertThat(triggerResult.getTriggerStatus().getMessage(), is("Connection Refused"));
        assertThat(triggerResult.getTriggerStatus().getState(), is(TriggerStatus.State.FAILED));
    }

    private void assertJobTriggerEquality(final List<JobTrigger> actual, final List<JobTrigger> expected) {
        assertThat(actual.size(), is(expected.size()));
        final Iterator<JobTrigger> actualTriggerIterator = actual.iterator();
        final Iterator<JobTrigger> expectedTriggerIterator = expected.iterator();
        while (expectedTriggerIterator.hasNext()) {
            assertThat(jobTriggerEquivalence.equivalent(expectedTriggerIterator.next(), actualTriggerIterator.next()), is(true));
        }
    }

    private final Equivalence<JobTrigger> jobTriggerEquivalence = new Equivalence<JobTrigger>() {
        @Override
        protected boolean doEquivalent(final JobTrigger a, final JobTrigger b) {
            final JobTrigger that = (JobTrigger) b;

            if (a.getDefinition() != null ? !a.getDefinition().equals(that.getDefinition()) : that.getDefinition() != null)
                return false;
            if (a.getTrigger() != null ? !a.getTrigger().equals(that.getTrigger()) : that.getTrigger() != null)
                return false;
            return !(a.getRunnable() != null ? !a.getRunnable().equals(that.getRunnable()) : that.getRunnable() != null);
        }

        @Override
        protected int doHash(final JobTrigger jobTrigger) {
            int result = jobTrigger.getDefinition() != null ? jobTrigger.getDefinition().hashCode() : 0;
            result = 31 * result + (jobTrigger.getTrigger() != null ? jobTrigger.getTrigger().hashCode() : 0);
            result = 31 * result + (jobTrigger.getRunnable() != null ? jobTrigger.getRunnable().hashCode() : 0);
            return result;
        }
    };

}
