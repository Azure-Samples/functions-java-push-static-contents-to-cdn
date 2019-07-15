package com.microsoft.azure.samples.functions;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.Optional;
import java.util.logging.Logger;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.AppServiceMSICredentials;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.cdn.CdnProfile;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;

/**
 * Azure Functions with HTTP Trigger.
 */
public class UploadFunction {
	
	private final MultipartFormUploadProcessor uploadProcessor;

	/**
	 * Retrieves CDN Profile to publish uploads based on App Settings
	 *
	 * @return The CDN Profile to which uploads will be published
	 */
	private static CdnProfile lookupCdnProfile() {
		String cdnSubscriptionId = System.getenv("CDN_SUBSCRIPTION_ID");
		String cdnResourceGroup = System.getenv("CDN_RESOURCE_GROUP");
		String cdnProfileName = System.getenv("CDN_PROFILE_NAME");

		Azure azure = Azure.authenticate(new AppServiceMSICredentials(AzureEnvironment.AZURE))
				.withSubscription(cdnSubscriptionId);
		return azure.cdnProfiles().getByResourceGroup(cdnResourceGroup, cdnProfileName);
	}

	/**
	 * Retrieves the Azure Storage Blob Container to store uploads based on App
	 * Settings
	 *
	 * @return The Blob container to which uploads will be published
	 */
	private static CloudBlobContainer lookupStorageContainer() {
		String storageAccountSubscriptionId = System.getenv("STORAGE_SUBSCRIPTION_ID");
		String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
		String storageContainerName = System.getenv("STORAGE_CONTAINER_NAME");
		String storageResourceGroup = System.getenv("STORAGE_RESOURCE_GROUP");
		Azure azure = Azure.authenticate(new AppServiceMSICredentials(AzureEnvironment.AZURE))
				.withSubscription(storageAccountSubscriptionId);
		StorageAccount storageAccountInfo = azure.storageAccounts().getByResourceGroup(storageResourceGroup,
				storageAccountName);
		String storageConnectionString = "DefaultEndpointsProtocol=https;" + "AccountName=" + storageAccountInfo.name()
				+ ";" + "AccountKey=" + storageAccountInfo.getKeys().get(0).value();
		try {
			CloudStorageAccount storageAccount = CloudStorageAccount.parse(storageConnectionString);
			CloudBlobClient blobClient = storageAccount.createCloudBlobClient();
			return blobClient.getContainerReference(storageContainerName);
		} catch (StorageException | URISyntaxException | InvalidKeyException e) {
			throw new IllegalStateException("Unable to access storage container " + storageContainerName, e);
		}
	}

	public UploadFunction() {
		String cdnEndpointName = System.getenv("CDN_ENDPOINT_NAME");
		CloudBlobContainer container = lookupStorageContainer();
		CdnProfile cdnProfile = lookupCdnProfile();
		uploadProcessor = new MultipartFormUploadProcessor(container, cdnProfile, cdnEndpointName);
	}

	/**
	 * Handles an HTTP POST request containing Multi-Part form content. Regions with
	 * a filename header are be persisted to a Blob container configured in App
	 * Settings and pushed to CDN.
	 * 
	 */
	@FunctionName("uploadFile")
	public HttpResponseMessage run(@HttpTrigger(name = "req", methods = {
			HttpMethod.POST }, authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<byte[]>> request,
			final ExecutionContext context) throws IOException {

		final Logger logger = context.getLogger();
		logger.info("Upload function triggered.");

		final byte[] body = request.getBody().orElseThrow(() -> new IllegalArgumentException("No content attached"));
		final String contentType = request.getHeaders().get("content-type");
		uploadProcessor.processUploadRequest(body, contentType, logger);
		return request.createResponseBuilder(HttpStatus.OK).build();
	}
}
