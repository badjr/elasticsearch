/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.snapshots;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesResponse;
import org.elasticsearch.action.admin.cluster.repositories.verify.VerifyRepositoryResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.RepositoriesMetadata;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.RepositoryConflictException;
import org.elasticsearch.repositories.RepositoryException;
import org.elasticsearch.repositories.RepositoryVerificationException;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.snapshots.mockstore.MockRepository;
import org.elasticsearch.test.ESIntegTestCase;

import java.nio.file.Path;
import java.util.List;

import static org.elasticsearch.repositories.blobstore.BlobStoreRepository.READONLY_SETTING_KEY;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertRequestBuilderThrows;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@ESIntegTestCase.ClusterScope(minNumDataNodes = 2)
public class RepositoriesIT extends AbstractSnapshotIntegTestCase {
    public void testRepositoryCreation() throws Exception {
        Client client = client();

        Path location = randomRepoPath();

        createRepository("test-repo-1", "fs", location);

        logger.info("--> verify the repository");
        int numberOfFiles = FileSystemUtils.files(location).length;
        VerifyRepositoryResponse verifyRepositoryResponse = client.admin().cluster().prepareVerifyRepository("test-repo-1").get();
        assertThat(verifyRepositoryResponse.getNodes().size(), equalTo(cluster().numDataAndMasterNodes()));

        logger.info("--> verify that we didn't leave any files as a result of verification");
        assertThat(FileSystemUtils.files(location).length, equalTo(numberOfFiles));

        logger.info("--> check that repository is really there");
        ClusterStateResponse clusterStateResponse = client.admin().cluster().prepareState().clear().setMetadata(true).get();
        Metadata metadata = clusterStateResponse.getState().getMetadata();
        RepositoriesMetadata repositoriesMetadata = metadata.custom(RepositoriesMetadata.TYPE);
        assertThat(repositoriesMetadata, notNullValue());
        assertThat(repositoriesMetadata.repository("test-repo-1"), notNullValue());
        assertThat(repositoriesMetadata.repository("test-repo-1").type(), equalTo("fs"));

        logger.info("-->  creating another repository");
        createRepository("test-repo-2", "fs");

        logger.info("--> check that both repositories are in cluster state");
        clusterStateResponse = client.admin().cluster().prepareState().clear().setMetadata(true).get();
        metadata = clusterStateResponse.getState().getMetadata();
        repositoriesMetadata = metadata.custom(RepositoriesMetadata.TYPE);
        assertThat(repositoriesMetadata, notNullValue());
        assertThat(repositoriesMetadata.repositories().size(), equalTo(2));
        assertThat(repositoriesMetadata.repository("test-repo-1"), notNullValue());
        assertThat(repositoriesMetadata.repository("test-repo-1").type(), equalTo("fs"));
        assertThat(repositoriesMetadata.repository("test-repo-2"), notNullValue());
        assertThat(repositoriesMetadata.repository("test-repo-2").type(), equalTo("fs"));

        logger.info("--> check that both repositories can be retrieved by getRepositories query");
        GetRepositoriesResponse repositoriesResponse = client.admin()
            .cluster()
            .prepareGetRepositories(randomFrom("_all", "*", "test-repo-*"))
            .get();
        assertThat(repositoriesResponse.repositories().size(), equalTo(2));
        assertThat(findRepository(repositoriesResponse.repositories(), "test-repo-1"), notNullValue());
        assertThat(findRepository(repositoriesResponse.repositories(), "test-repo-2"), notNullValue());

        logger.info("--> check that trying to create a repository with the same settings repeatedly does not update cluster state");
        String beforeStateUuid = clusterStateResponse.getState().stateUUID();
        assertThat(
            client.admin()
                .cluster()
                .preparePutRepository("test-repo-1")
                .setType("fs")
                .setSettings(Settings.builder().put("location", location))
                .get()
                .isAcknowledged(),
            equalTo(true)
        );
        assertEquals(beforeStateUuid, client.admin().cluster().prepareState().clear().get().getState().stateUUID());

        logger.info("--> delete repository test-repo-1");
        client.admin().cluster().prepareDeleteRepository("test-repo-1").get();
        repositoriesResponse = client.admin().cluster().prepareGetRepositories().get();
        assertThat(repositoriesResponse.repositories().size(), equalTo(1));
        assertThat(findRepository(repositoriesResponse.repositories(), "test-repo-2"), notNullValue());

        logger.info("--> delete repository test-repo-2");
        client.admin().cluster().prepareDeleteRepository("test-repo-2").get();
        repositoriesResponse = client.admin().cluster().prepareGetRepositories().get();
        assertThat(repositoriesResponse.repositories().size(), equalTo(0));
    }

    private RepositoryMetadata findRepository(List<RepositoryMetadata> repositories, String name) {
        for (RepositoryMetadata repository : repositories) {
            if (repository.name().equals(name)) {
                return repository;
            }
        }
        return null;
    }

    public void testMisconfiguredRepository() {
        Client client = client();

        logger.info("--> trying creating repository with incorrect settings");
        try {
            client.admin().cluster().preparePutRepository("test-repo").setType("fs").get();
            fail("Shouldn't be here");
        } catch (RepositoryException ex) {
            assertThat(ex.getCause().getMessage(), equalTo("[test-repo] missing location"));
        }

        logger.info("--> trying creating fs repository with location that is not registered in path.repo setting");
        Path invalidRepoPath = createTempDir().toAbsolutePath();
        String location = invalidRepoPath.toString();
        try {
            client().admin()
                .cluster()
                .preparePutRepository("test-repo")
                .setType("fs")
                .setSettings(Settings.builder().put("location", location))
                .get();
            fail("Shouldn't be here");
        } catch (RepositoryException ex) {
            assertThat(
                ex.getCause().getMessage(),
                containsString("location [" + location + "] doesn't match any of the locations specified by path.repo")
            );
        }
    }

    public void testRepositoryAckTimeout() {
        logger.info("-->  creating repository test-repo-1 with 0s timeout - shouldn't ack");
        AcknowledgedResponse putRepositoryResponse = client().admin()
            .cluster()
            .preparePutRepository("test-repo-1")
            .setType("fs")
            .setSettings(
                Settings.builder()
                    .put("location", randomRepoPath())
                    .put("compress", randomBoolean())
                    .put("chunk_size", randomIntBetween(5, 100), ByteSizeUnit.BYTES)
            )
            .setTimeout("0s")
            .get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(false));

        logger.info("-->  creating repository test-repo-2 with standard timeout - should ack");
        putRepositoryResponse = client().admin()
            .cluster()
            .preparePutRepository("test-repo-2")
            .setType("fs")
            .setSettings(
                Settings.builder()
                    .put("location", randomRepoPath())
                    .put("compress", randomBoolean())
                    .put("chunk_size", randomIntBetween(5, 100), ByteSizeUnit.BYTES)
            )
            .get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        logger.info("-->  deleting repository test-repo-2 with 0s timeout - shouldn't ack");
        AcknowledgedResponse deleteRepositoryResponse = client().admin()
            .cluster()
            .prepareDeleteRepository("test-repo-2")
            .setTimeout("0s")
            .get();
        assertThat(deleteRepositoryResponse.isAcknowledged(), equalTo(false));

        logger.info("-->  deleting repository test-repo-1 with standard timeout - should ack");
        deleteRepositoryResponse = client().admin().cluster().prepareDeleteRepository("test-repo-1").get();
        assertThat(deleteRepositoryResponse.isAcknowledged(), equalTo(true));
    }

    public void testRepositoryVerification() {
        disableRepoConsistencyCheck("This test does not create any data in the repository.");

        Client client = client();

        Settings settings = Settings.builder().put("location", randomRepoPath()).put("random_control_io_exception_rate", 1.0).build();
        Settings readonlySettings = Settings.builder().put(settings).put(READONLY_SETTING_KEY, true).build();
        logger.info("-->  creating repository that cannot write any files - should fail");
        assertRequestBuilderThrows(
            client.admin().cluster().preparePutRepository("test-repo-1").setType("mock").setSettings(settings),
            RepositoryVerificationException.class
        );

        logger.info("-->  creating read-only repository that cannot read any files - should fail");
        assertRequestBuilderThrows(
            client.admin().cluster().preparePutRepository("test-repo-2").setType("mock").setSettings(readonlySettings),
            RepositoryVerificationException.class
        );

        logger.info("-->  creating repository that cannot write any files, but suppress verification - should be acked");
        assertAcked(client.admin().cluster().preparePutRepository("test-repo-1").setType("mock").setSettings(settings).setVerify(false));

        logger.info("-->  verifying repository");
        assertRequestBuilderThrows(client.admin().cluster().prepareVerifyRepository("test-repo-1"), RepositoryVerificationException.class);

        logger.info("-->  creating read-only repository that cannot read any files, but suppress verification - should be acked");
        assertAcked(
            client.admin().cluster().preparePutRepository("test-repo-2").setType("mock").setSettings(readonlySettings).setVerify(false)
        );

        logger.info("-->  verifying repository");
        assertRequestBuilderThrows(client.admin().cluster().prepareVerifyRepository("test-repo-2"), RepositoryVerificationException.class);

        Path location = randomRepoPath();

        logger.info("-->  creating repository");
        try {
            client.admin()
                .cluster()
                .preparePutRepository("test-repo-1")
                .setType("mock")
                .setSettings(Settings.builder().put("location", location).put("localize_location", true))
                .get();
            fail("RepositoryVerificationException wasn't generated");
        } catch (RepositoryVerificationException ex) {
            assertThat(ExceptionsHelper.stackTrace(ex), containsString("is not shared"));
        }
    }

    public void testRepositoryConflict() throws Exception {
        logger.info("--> creating repository");
        final String repo = "test-repo";
        assertAcked(
            client().admin()
                .cluster()
                .preparePutRepository(repo)
                .setType("mock")
                .setSettings(
                    Settings.builder()
                        .put("location", randomRepoPath())
                        .put("random", randomAlphaOfLength(10))
                        .put("wait_after_unblock", 200)
                )
                .get()
        );

        logger.info("--> snapshot");
        final String index = "test-idx";
        assertAcked(prepareCreate(index, 1, Settings.builder().put("number_of_shards", 1).put("number_of_replicas", 0)));
        for (int i = 0; i < 10; i++) {
            indexDoc(index, Integer.toString(i), "foo", "bar" + i);
        }
        refresh();
        final String snapshot1 = "test-snap1";
        client().admin().cluster().prepareCreateSnapshot(repo, snapshot1).setWaitForCompletion(true).get();
        String blockedNode = internalCluster().getMasterName();
        ((MockRepository) internalCluster().getInstance(RepositoriesService.class, blockedNode).repository(repo)).blockOnDataFiles();
        logger.info("--> start deletion of snapshot");
        ActionFuture<AcknowledgedResponse> future = client().admin().cluster().prepareDeleteSnapshot(repo, snapshot1).execute();
        logger.info("--> waiting for block to kick in on node [{}]", blockedNode);
        waitForBlock(blockedNode, repo);

        logger.info("--> try deleting the repository, should fail because the deletion of the snapshot is in progress");
        RepositoryConflictException e1 = expectThrows(
            RepositoryConflictException.class,
            () -> client().admin().cluster().prepareDeleteRepository(repo).get()
        );
        assertThat(e1.status(), equalTo(RestStatus.CONFLICT));
        assertThat(e1.getMessage(), containsString("trying to modify or unregister repository that is currently used"));

        logger.info("--> try updating the repository, should fail because the deletion of the snapshot is in progress");
        RepositoryConflictException e2 = expectThrows(
            RepositoryConflictException.class,
            () -> client().admin()
                .cluster()
                .preparePutRepository(repo)
                .setType("mock")
                .setSettings(Settings.builder().put("location", randomRepoPath()))
                .get()
        );
        assertThat(e2.status(), equalTo(RestStatus.CONFLICT));
        assertThat(e2.getMessage(), containsString("trying to modify or unregister repository that is currently used"));

        logger.info("--> unblocking blocked node [{}]", blockedNode);
        unblockNode(repo, blockedNode);

        logger.info("--> wait until snapshot deletion is finished");
        assertAcked(future.actionGet());
    }
}
