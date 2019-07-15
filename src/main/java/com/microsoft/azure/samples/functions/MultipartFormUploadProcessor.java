package com.microsoft.azure.samples.functions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.fileupload.MultipartStream;

import com.microsoft.azure.management.cdn.CdnProfile;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;

/**
 * Processes content of multipart form uploads to Azure Storage and pushes newly
 * uploaded content to CDN.
 * 
 * @author yevster
 *
 */
public class MultipartFormUploadProcessor {

	private final CloudBlobContainer blobContainer;
	private final CdnProfile cdnProfile;
	private final String cdnEndpointName;

	/**
	 * Configures a new upload processor
	 * 
	 * @param blobContainer   An authenticated Azure SDK blob container to which
	 *                        uploaded files will be persited
	 * @param cdnProfile      An authenticated Azure SDK CDN Profile to which
	 *                        uploaded files will be pushed
	 * @param cdnEndpointName Then name of the CDN endpoint to which uploaded files
	 *                        will be pushed
	 */
	public MultipartFormUploadProcessor(CloudBlobContainer blobContainer, CdnProfile cdnProfile,
			String cdnEndpointName) {
		this.blobContainer = blobContainer;
		this.cdnProfile = cdnProfile;
		this.cdnEndpointName = cdnEndpointName;
	}

	/**
	 * Handles a multipart upload request in memory.
	 * All regions with a `filename` header are persisted to Blob Storage and pushed to CDN.
	 * All other regions are ignored.
	 * @param body The body of the HTTP request
	 * @param contentType The full value of the HTTP `Content-Type` request header
	 * @param logger The logger to be used for status output
	 * @throws IOException
	 */
	public void processUploadRequest(final byte[] body, final String contentType, final Logger logger)
			throws IOException {

		String boundary = contentType.split(";")[1].split("=")[1]; // Get boundary from content-type header
		Set<String> uploadedPaths = Collections.emptySet();
		try (InputStream is = new ByteArrayInputStream(body)) {
			MultipartStream multiPartStream = new MultipartStream(is, boundary.getBytes(), 1024, null);
	
			uploadedPaths = PartStreamFactory.asStream(multiPartStream)
				// Select parts with a filename field...
				.filter(part -> part.getHeader().contains("filename"))
				// And non-empty bodies
				.filter(part -> part.getBody().length > 0)
				// Upload theses to storage
				.map(part -> {
					String filename = readFileNameFromPartHeader(part.getHeader()).get();
					logger.info("Uploading "+filename);
					return uploadFile(filename, part.getBody()).orElseThrow(()-> new IllegalStateException("Unable to upload "+filename));
				})
				// Get path to push to CDN
				.map(b -> "/" + blobContainer.getName() + "/" + b.getName())
				// Log upload success
				.peek(name -> logger.info("Uploaded " + name))
				// Collect content paths
				.collect(Collectors.toSet());
		}

		if (!uploadedPaths.isEmpty()) {
			logger.info("Pushing to CDN");
			cdnProfile.loadEndpointContentAsync(cdnEndpointName, uploadedPaths)
					.doOnError(t -> logger.log(Level.SEVERE, "Error on CDN Update", t));
		}
	}

	
	/**
	 * Extracts the filename value from a form part header (if present)
	 * @param header A form part header
	 * @return The filename, if present. Empty if no filename is present.
	 */
	private static Optional<String> readFileNameFromPartHeader(String header) {
		int filenameStart = header.indexOf("filename=") + "filename=".length() + 1;
		if (filenameStart == "filename=".length()) // No filename in header
			return Optional.empty();
		int filenameEnd = header.indexOf("\r\n") - 1;
		return Optional.of(header.substring(filenameStart, filenameEnd));
	}

	/**
	 * Uploads a file to Azure Blob Storage.
	 * @param filename The name of the file being uploaded
	 * @param body The content of the file being uploaded
	 * @return The Azure SDK Blob object, if the upload is successful. Empty if the upload has failed.
	 */
	protected Optional<CloudBlockBlob> uploadFile(String filename, byte[] body) {
		CloudBlockBlob blob = null;
		try {
			blob = blobContainer.getBlockBlobReference(filename);
			blob.uploadFromByteArray(body, 0, body.length);
		} catch (StorageException | URISyntaxException | IOException e) {
			throw new IllegalStateException("Unable to upload file " + filename, e);
		}
		return Optional.ofNullable(blob);
	}
}
