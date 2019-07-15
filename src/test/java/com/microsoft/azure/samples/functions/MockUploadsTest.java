package com.microsoft.azure.samples.functions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableSet;
import com.microsoft.azure.management.cdn.CdnProfile;
import com.microsoft.azure.samples.functions.MultipartFormUploadProcessor;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;

import rx.Completable;

public class MockUploadsTest {

	private final Logger logger = Logger.getLogger(MockUploadsTest.class.getName());
	private CdnProfile mockCdnProfile;
	private final String cdnEndpointName = "mockEndpointName";

	@BeforeEach
	public void initialize() throws StorageException, URISyntaxException {
		mockCdnProfile = Mockito.mock(CdnProfile.class, Mockito.RETURNS_DEEP_STUBS);
	}

	@Test
	public void testNonFileData() throws IOException, StorageException, URISyntaxException {
		final String contentTypeHeader = "multipart/form-data; boundary=------------------------fkgjsl4jtehj4htej4htkej";
		final String requestBody = "Content-Type: multipart/form-data; boundary=------------------------fkgjsl4jtehj4htej4htkej\r\n"
				+ "--------------------------fkgjsl4jtehj4htej4htkej\r\n"
				+ "Content-Disposition: form-data; name=\"firstName\"\r\n\r\n" + "Joe\r\n"
				+ "--------------------------fkgjsl4jtehj4htej4htkej\r\n"
				+ "Content-Disposition: form-data; name=\"lastName\"\r\n\r\n" + "Schmoe\r\n"
				+ "--------------------------fkgjsl4jtehj4htej4htkej--";

		CloudBlobContainer failContainer = new CloudBlobContainer(new URI("https://example.org/this-will-fail"));

		try {
			new MultipartFormUploadProcessor(failContainer, mockCdnProfile, "dummyEndpoint")
					.processUploadRequest(requestBody.getBytes(), contentTypeHeader, logger);
		} catch (IllegalStateException e) {
			Assertions.fail("Upload attempted when form data contained no files.");
		}

		// Ensure no paths are submitted to a CDN push
		verify(mockCdnProfile, never()).loadEndpointContentAsync(anyString(), any());
	}

	@Test
	public void testHybridForm() throws StorageException, URISyntaxException, IOException {
		final String contentTypeHeader = "multipart/form-data; boundary=------------------------fkgjsl4jtehj4htej4htkej";
		final String requestBody = "Content-Type: multipart/form-data; boundary=------------------------fkgjsl4jtehj4htej4htkej\r\n"
				+ "--------------------------fkgjsl4jtehj4htej4htkej\r\n"
				+ "Content-Disposition: form-data; name=\"firstName\"\r\n\r\n" + "Joe\r\n"
				+ "--------------------------fkgjsl4jtehj4htej4htkej\r\n"
				+ "Content-Disposition: form-data; name=\"myfile\"; filename=\"lifestory.txt\"\r\n"
				+ "Content-Type: text/plain\r\n\r\n" + "Started from the bottom; now I'm here!\r\n"
				+ "--------------------------fkgjsl4jtehj4htej4htkej--";

		CloudBlobContainer failContainer = new CloudBlobContainer(new URI("https://example.org/this-will-fail"));
		
		// Need to work around the final-ity of CloudBlobContainer
		AtomicBoolean mockUploadSuccess = new AtomicBoolean(false);
		MultipartFormUploadProcessor testUploader = new MultipartFormUploadProcessor(failContainer, mockCdnProfile, cdnEndpointName) {
			@Override
			protected Optional<CloudBlockBlob> uploadFile(String filename, byte[] body) {
				Assertions.assertEquals("lifestory.txt", filename);
				Assertions.assertEquals("Started from the bottom; now I'm here!", new String(body));
				CloudBlockBlob result = null;
				try {
					result = new CloudBlockBlob(new URI("https://example.org/fakecontainer/lifestory.txt"));
					mockUploadSuccess.set(true);
				} catch (StorageException | URISyntaxException e) {
					Assertions.fail(e);
				}
				return Optional.of(result);
			}
		};

		// Mock the CDN, make sure the correct path (/containername/filename) gets pushed
		Mockito.when(mockCdnProfile.loadEndpointContentAsync(anyString(), any())).thenAnswer(invocation -> {
			Assertions.assertEquals(cdnEndpointName, invocation.getArgument(0));
			Assertions.assertEquals(ImmutableSet.of("/this-will-fail/lifestory.txt"), invocation.getArgument(1));
			return Completable.complete();
		});

		// Run and verify
		testUploader.processUploadRequest(requestBody.getBytes(), contentTypeHeader, logger);
		Assertions.assertTrue(mockUploadSuccess.get());
		verify(mockCdnProfile, times(1)).loadEndpointContentAsync(Mockito.anyString(), Mockito.any());
	}
}
