#!/bin/bash

. deployment.properties

# Enable MSI authentication on Functions App
az webapp identity assign --name "$functionAppName" --resource-group "$functionAppResourceGroup"
 #Wait for the service principal to propagate.
sleep 10s

# Create the storage resource group (unless it already exists)
az account set --subscription $storageSubscriptionId
existingStorageRG=$(az group show --name "$storageResourceGroup" 2>/dev/null)
if [ -z "$existingStorageRG" ]; then 
    az group create --name "$storageResourceGroup" --location "$location" 
fi

# Create the storage account
storageAccountId=$(az storage account create -otsv --query 'id'\
    --resource-group "$storageResourceGroup" --name "$storageAccountName" \
    --location "$location" --sku "Standard_LRS")
blobUrl=$(az storage account show --ids $storageAccountId --query 'primaryEndpoints.blob' -otsv)
blobHost=$(echo $blobUrl | cut -d '/' -f3)

# Create a container in blob storage to host uploads
az storage container create --name "$storageContainerName" \
    --account-name "$storageAccountName" --public-access blob

# Create the CDN resource group (unless it already exists)
az account set --subscription $cdnSubscriptionId
existingCdnRG=$(az group show --name "$cdnResourceGroup" 2>/dev/null)
if [ -z "$existingCdnRG" ]; then 
    az group create --name "$cdnResourceGroup" --location "$location" 
fi

# Create a CDN Profile (using Verizon, in order to push content)
cdnProfileId=$(az cdn profile create -otsv --query id \
    --resource-group "$cdnResourceGroup" --name "$cdnProfileName" \
    --sku Standard_Verizon --location "$location")

# Create a CDN Endpoint with settings from cdn.properties
. cdn.properties
az cdn endpoint create --location "$location" --name "$cdnEndpointName" \
    --profile-name "$cdnProfileName" --resource-group "$cdnResourceGroup" \
    --origin "$blobHost" --origin-host-header "$blobHost" \
    --enable-compression "$enable_compression" \
    --content-types-to-compress ${content_types_to_compress//,/ } \
    --no-http "$https_only"

# Grant the Functions App the permission to read and write to the storage account
functionSpAppId=$(az ad sp list --display-name "$functionAppName" --query '[].appId' -otsv)
az role assignment create --scope "$storageAccountId" --assignee "$functionSpAppId" --role Contributor
# Grant the Functions app the permission to read and write to the CDN profile
az role assignment create --scope "$cdnProfileId" --assignee "$functionSpAppId" --role Contributor

# Create a test webpage
functionAppHostName=$(az functionapp show --query defaultHostName -otsv \
    --name "$functionAppName" --resource-group "$functionAppResourceGroup")
cp -f upload.html.template upload.html 
sed -i 's/${HOSTNAME}/'${functionAppHostName}'/g' upload.html
echo "Open upload.html in a web browser to upload a file."
echo "Note: you may need to wait about 90 minutes for the CDN registration to propagate."
