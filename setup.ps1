#!/usr/bin/env pwsh

$config = Get-Content -Raw ./deployment.properties | ConvertFrom-StringData

# Enable MSI authentication on Functions App
$webApp = Set-AzWebApp -AssignIdentity $true -Name $config.functionAppName -ResourceGroupName $config.functionAppResourceGroup
Start-Sleep -Seconds 10 #Wait for the service principal to propagate.

# Create the storage resource group (unless it already exists)
Set-AzContext -Subscription $config.storageSubscriptionId > $null
$storageResourceGroup = Get-AzResourceGroup -Name $config.storageResourceGroup 2>$null
if (!$storageResourceGroup) { $storageResourceGroup = New-AzResourceGroup -Name $config.storageResourceGroup -Location $config.location }

# Create the storage account
$storageAccount = New-AzStorageAccount -ResourceGroupName $storageResourceGroup.ResourceGroupName `
    -Name $config.storageAccountName -SkuName Standard_LRS `
    -Location $storageResourceGroup.Location
$blobEndpoint = $storageAccount.PrimaryEndpoints.Blob
$cdnHost = ([System.Uri]$blobEndpoint).Host

# Create a container in blob storage to host uploads
$storageAccount | Set-AzCurrentStorageAccount
$storageContainer = New-AzStorageContainer -Name $config.storageContainerName -Permission Blob

# Create the CDN resource group (unless it already exists)
Set-AzContext -Subscription $config.cdnSubscriptionId > $null
$cdnResourceGroup = Get-AzResourceGroup -Name $config.cdnResourceGroup 2>$null
if (!$cdnResourceGroup) { $cdnResourceGroup = New-AzResourceGroup -Location $config.location -Name $config.cdnResourceGroup }

# Create a CDN Profile (using Verizon, in order to push content)
$cdnProfile = New-AzCdnProfile -ProfileName $config.cdnProfileName -ResourceGroupName $config.cdnResourceGroup -Sku Standard_Verizon -Location $config.location

# Create a CDN Endpoint with settings from cdn.properties
$cdnConfig = Get-Content -Raw ./cdn.properties | ConvertFrom-StringData
$endpointParams = @{
    OriginName = $config.storageAccountName;
    OriginHostName = $cdnHost; 
    OriginHostHeader = $cdnHost; # Must match origin host for use with Azure Storage
    IsHttpAllowed = !([bool]$cdnConfig.'https_only');
    IsCompressionEnabled = [bool]$cdnConfig.'enable_compression';
    ContentTypesToCompress = $cdnConfig.'content_types_to_compress'.Split(',');
}
$cdnEndpoint = New-AzCdnEndpoint -EndpointName $config.cdnEndpointName `
    -CdnProfile $cdnProfile @endpointParams

# Grant the Functions App the permission to read and write to the storage account
$appServicePrincipal = Get-AzADServicePrincipal -DisplayName $config.functionAppName
New-AzRoleAssignment -Scope $storageAccount.Id -RoleDefinitionName Contributor -ApplicationId $appServicePrincipal.ApplicationId > $null
# Grant the Functions app the permission to read and write to the CDN profile
New-AzRoleAssignment -Scope $cdnProfile.Id -RoleDefinitionName Contributor -ApplicationId $appServicePrincipal.ApplicationId > $null

# Create a test webpage
$pageTemplate = Get-Content -Raw '.\upload.html.template'
$pageTemplate.Replace('${HOSTNAME}', $webapp.DefaultHostName) > .\upload.html
Write-Host "Open upload.html in a web browser to upload a file."
Write-Host "Note: you may need to wait about 90 minutes for the CDN registration to propagate."
