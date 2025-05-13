package com.github.dockerjava.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.DockerObjectAccessor;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Link;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.VolumesFrom;
import com.github.dockerjava.junit.DockerAssume;
import com.github.dockerjava.junit.PrivateRegistryRule;
import com.github.dockerjava.utils.TestUtils;
import net.jcip.annotations.NotThreadSafe;
import org.apache.commons.io.FileUtils;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.dockerjava.api.model.Capability.MKNOD;
import static com.github.dockerjava.api.model.Capability.NET_ADMIN;
import static com.github.dockerjava.api.model.HostConfig.newHostConfig;
import static com.github.dockerjava.core.RemoteApiVersion.VERSION_1_23;
import static com.github.dockerjava.core.RemoteApiVersion.VERSION_1_24;
import static com.github.dockerjava.core.RemoteApiVersion.VERSION_1_43;
import static com.github.dockerjava.junit.DockerMatchers.isGreaterOrEqual;
import static com.github.dockerjava.junit.DockerMatchers.mountedVolumes;
import static com.github.dockerjava.core.DockerRule.DEFAULT_IMAGE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeThat;

@NotThreadSafe
public class CreateContainerCmdIT extends CmdIT {
    public static final Logger LOG = LoggerFactory.getLogger(CreateContainerCmdIT.class);

    @ClassRule
    public static PrivateRegistryRule REGISTRY = new PrivateRegistryRule();

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder(new File("target/"));

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test(expected = ConflictException.class)
    public void createContainerWithExistingName() throws DockerException {

        String containerName = "generated_" + new SecureRandom().nextInt();

        CreateContainerResponse container = dockerRule.getClient().createContainerCmd(DEFAULT_IMAGE).withCmd("env")
                .withName(containerName).exec();

        LOG.info("Created container {}", container.toString());

        assertThat(container.getId(), not(is(emptyString())));

        dockerRule.getClient().createContainerCmd(DEFAULT_IMAGE).withCmd("env").withName(containerName).exec();
    }

    @Test
    public void createContainerWithAnnotations() throws DockerException {
        assumeThat("API version should be >= 1.43", dockerRule, isGreaterOrEqual(VERSION_1_43));

        // Test case 1: Basic annotations
        Map<String, String> annotations = new HashMap<>();
        annotations.put("com.example.key1", "value1");
        annotations.put("com.example.key2", "value2");

        LOG.info("Test case 1: Creating container with basic annotations: {}", annotations);

        CreateContainerResponse container = dockerRule.getClient().createContainerCmd(DEFAULT_IMAGE)
                .withCmd("sleep", "9999")
                .withHostConfig(newHostConfig()
                        .withAnnotations(annotations))
                .exec();

        LOG.info("Created container with ID: {}", container.getId());
        assertThat(container.getId(), not(is(emptyString())));

        InspectContainerResponse inspectContainerResponse = dockerRule.getClient().inspectContainerCmd(container.getId()).exec();
        LOG.info("Container inspection response: {}", inspectContainerResponse);
        LOG.info("HostConfig from inspection: {}", inspectContainerResponse.getHostConfig());
        LOG.info("Annotations from inspection: {}", inspectContainerResponse.getHostConfig().getAnnotations());

        assertThat(inspectContainerResponse.getHostConfig().getAnnotations(), equalTo(annotations));
        assertThat(inspectContainerResponse.getHostConfig().getAnnotations().get("com.example.key1"), equalTo("value1"));
        assertThat(inspectContainerResponse.getHostConfig().getAnnotations().get("com.example.key2"), equalTo("value2"));

        // Test case 2: Annotations with null value
        Map<String, String> annotationsWithNull = new HashMap<>();
        annotationsWithNull.put("com.example.null", null);
        annotationsWithNull.put("com.example.key3", "value3");

        LOG.info("Test case 2: Creating container with annotations including null value: {}", annotationsWithNull);

        CreateContainerResponse container2 = dockerRule.getClient().createContainerCmd(DEFAULT_IMAGE)
                .withCmd("sleep", "9999")
                .withHostConfig(newHostConfig()
                        .withAnnotations(annotationsWithNull))
                .exec();

        LOG.info("Created container2 with ID: {}", container2.getId());
        assertThat(container2.getId(), not(is(emptyString())));

        InspectContainerResponse inspectContainerResponse2 = dockerRule.getClient().inspectContainerCmd(container2.getId()).exec();
        LOG.info("Container2 inspection response: {}", inspectContainerResponse2);
        LOG.info("HostConfig from inspection2: {}", inspectContainerResponse2.getHostConfig());
        LOG.info("Annotations from inspection2: {}", inspectContainerResponse2.getHostConfig().getAnnotations());

        // Test case 3: Annotations with special characters
        Map<String, String> annotationsWithSpecialChars = new HashMap<>();
        annotationsWithSpecialChars.put("com.example.special", "value with spaces and @#$%^&*()");
        annotationsWithSpecialChars.put("com.example.unicode", "value with unicode: 你好");

        LOG.info("Test case 3: Creating container with annotations including special characters: {}", annotationsWithSpecialChars);

        CreateContainerResponse container3 = dockerRule.getClient().createContainerCmd(DEFAULT_IMAGE)
                .withCmd("sleep", "9999")
                .withHostConfig(newHostConfig()
                        .withAnnotations(annotationsWithSpecialChars))
                .exec();

        LOG.info("Created container3 with ID: {}", container3.getId());
        assertThat(container3.getId(), not(is(emptyString())));

        InspectContainerResponse inspectContainerResponse3 = dockerRule.getClient().inspectContainerCmd(container3.getId()).exec();
        LOG.info("Container3 inspection response: {}", inspectContainerResponse3);
        LOG.info("HostConfig from inspection3: {}", inspectContainerResponse3.getHostConfig());
        LOG.info("Annotations from inspection3: {}", inspectContainerResponse3.getHostConfig().getAnnotations());
    }
}
