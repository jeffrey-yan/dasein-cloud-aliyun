/*
 *  *
 *  Copyright (C) 2009-2015 Dell, Inc.
 *  See annotations for authorship information
 *
 *  ====================================================================
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  ====================================================================
 *
 */

package org.dasein.cloud.aliyun.storage;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.aliyun.Aliyun;
import org.dasein.cloud.aliyun.AliyunException;
import org.dasein.cloud.aliyun.storage.model.AccessControlPolicy;
import org.dasein.cloud.aliyun.storage.model.CompleteMultipartUpload;
import org.dasein.cloud.aliyun.storage.model.CompleteMultipartUploadResult;
import org.dasein.cloud.aliyun.storage.model.CopyObjectResult;
import org.dasein.cloud.aliyun.storage.model.CopyPartResult;
import org.dasein.cloud.aliyun.storage.model.CreateBucketConfiguration;
import org.dasein.cloud.aliyun.storage.model.InitiateMultipartUploadResult;
import org.dasein.cloud.aliyun.storage.model.ListAllMyBucketsResult;
import org.dasein.cloud.aliyun.storage.model.ListBucketResult;
import org.dasein.cloud.aliyun.storage.model.LocationConstraint;
import org.dasein.cloud.aliyun.util.requester.AliyunHttpClientBuilderFactory;
import org.dasein.cloud.aliyun.util.requester.AliyunRequestBuilder;
import org.dasein.cloud.aliyun.util.requester.AliyunRequestBuilderStrategy;
import org.dasein.cloud.aliyun.util.requester.AliyunRequestExecutor;
import org.dasein.cloud.aliyun.util.requester.AliyunResponseException;
import org.dasein.cloud.aliyun.util.requester.AliyunResponseHandler;
import org.dasein.cloud.aliyun.util.requester.AliyunValidateEmptyResponseHandler;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.storage.AbstractBlobStoreSupport;
import org.dasein.cloud.storage.Blob;
import org.dasein.cloud.storage.BlobStoreCapabilities;
import org.dasein.cloud.storage.BlobStoreSupport;
import org.dasein.cloud.storage.FileTransfer;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.requester.streamprocessors.StreamToStringProcessor;
import org.dasein.cloud.util.requester.streamprocessors.XmlStreamToObjectProcessor;
import org.dasein.util.uom.storage.Byte;
import org.dasein.util.uom.storage.Megabyte;
import org.dasein.util.uom.storage.Storage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Jeffrey Yan on 7/7/2015.
 *
 * @author Jeffrey Yan
 * @since 2015.09.1
 */
public class AliyunBlobStore extends AbstractBlobStoreSupport<Aliyun> implements BlobStoreSupport{

    static private final Logger stdLogger = Aliyun.getStdLogger(AliyunBlobStore.class);

    static private final double MULTIPART_UPLOAD_COPY_THRESHOLD_IN_GB = 1.0;
    static private final double MULTIPART_UPLOAD_COPY_PART_SIZE_IN_MB = 500.0;

    protected AliyunBlobStore(Aliyun provider) {
        super(provider);
    }

    @Nonnull
    @Override
    public BlobStoreCapabilities getCapabilities() throws CloudException, InternalException {
        return new AliyunBlobStoreCapabilities(getProvider());
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Override
    protected void get(@Nullable final String bucket, @Nonnull String object, @Nonnull File toFile,
            @Nullable final FileTransfer transfer) throws InternalException, CloudException {
        final String regionId = getContext().getRegionId();
        if (regionId == null) {
            throw new InternalException("No region was set for this request");
        }

        final FileOutputStream fileOutputStream;
        try {
            fileOutputStream = new FileOutputStream(toFile);
        } catch (FileNotFoundException fileNotFoundException) {
            stdLogger.error(fileNotFoundException);
            throw new InternalException(fileNotFoundException);
        }

        HttpUriRequest request = AliyunRequestBuilder.get()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .path("/" + object)
                .build();

        ResponseHandler<Void> responseHandler = new ResponseHandler<Void>(){
            @Override
            public Void handleResponse(HttpResponse response) throws IOException {
                int httpCode = response.getStatusLine().getStatusCode();
                if(httpCode == HttpStatus.SC_OK ) {
                    InputStream inputStream = response.getEntity().getContent();
                    copy(inputStream, fileOutputStream, transfer);
                    return null;
                } else {
                    stdLogger.error("Unexpected OK for HEAD request, got " + httpCode);
                    throw new AliyunResponseException(httpCode, null, null,
                            response.getFirstHeader("x-oss-request-id").getValue(), generateHost(regionId, bucket));
                }
            }
        };

        new AliyunRequestExecutor<Void>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                responseHandler).execute();
    }

    @Override
    protected void put(@Nullable String bucket, @Nonnull String objectName, @Nonnull File file)
            throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.put()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .path("/" + objectName)
                .entity(file)
                .build();

        new AliyunRequestExecutor<String>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                new AliyunValidateEmptyResponseHandler()).execute();
    }

    @Override
    protected void put(@Nullable String bucketName, @Nonnull String objectName, @Nonnull String content)
            throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.put()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucketName)
                .path("/" + objectName)
                .entity(content, new StreamToStringProcessor())
                .build();

        new AliyunRequestExecutor<String>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                new AliyunValidateEmptyResponseHandler()).execute();
    }

    private String generateHost(String regionId, String bucket) {
        return bucket + ".oss-" + regionId + ".aliyuncs.com";
    }

    private String generateUrl(String regionId, String bucket, String object) {
        if(object == null) {
            return "http://" + bucket + ".oss-" + regionId + ".aliyuncs.com";
        } else {
            return "http://" + bucket + ".oss-" + regionId + ".aliyuncs.com/" + object;
        }
    }

    @Nonnull
    @Override
    public Blob createBucket(@Nonnull String bucket, boolean findFreeName) throws InternalException, CloudException {
        String regionId = getContext().getRegionId();
        if (regionId == null) {
            throw new InternalException("No region was set for this request");
        }

        CreateBucketConfiguration createBucketConfiguration = new CreateBucketConfiguration();
        createBucketConfiguration.setLocationConstraint("oss-" + regionId);

        HttpUriRequest request = AliyunRequestBuilder.put()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .header("x-oss-acl", "private")
                .entity(createBucketConfiguration, new XmlStreamToObjectProcessor<CreateBucketConfiguration>())
                .build();

        ResponseHandler<String> responseHandler = new AliyunResponseHandler<String>(
                new StreamToStringProcessor(),
                String.class);

       new AliyunRequestExecutor<String>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                responseHandler).execute();

        return Blob.getInstance(regionId, generateUrl(regionId, bucket, null), bucket,
                System.currentTimeMillis());
    }

    @Override
    public boolean exists(@Nonnull String bucket) throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.get()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .parameter("location", null)
                .build();

        ResponseHandler<LocationConstraint> responseHandler = new AliyunResponseHandler<LocationConstraint>(
                new XmlStreamToObjectProcessor(),
                LocationConstraint.class);

        try {
           new AliyunRequestExecutor<LocationConstraint>(getProvider(),
                    AliyunHttpClientBuilderFactory.newHttpClientBuilder(), request, responseHandler).execute();
           return true;
        } catch (AliyunException aliyunException) {
            if (aliyunException.getHttpCode() == HttpStatus.SC_FORBIDDEN) {
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public Blob getBucket(@Nonnull String bucketName) throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.get()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .build();

        ResponseHandler<ListAllMyBucketsResult> responseHandler = new AliyunResponseHandler<ListAllMyBucketsResult>(
                new XmlStreamToObjectProcessor(),
                ListAllMyBucketsResult.class);

        ListAllMyBucketsResult result = new AliyunRequestExecutor<ListAllMyBucketsResult>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                responseHandler).execute();

        for (ListAllMyBucketsResult.Bucket bucket : result.getBuckets()) {
            if (bucket.getName().equals(bucketName)) {
                String location = bucket.getLocation();
                String regionId = location.substring(location.indexOf('-') + 1);
                return Blob.getInstance(regionId, generateUrl(regionId, bucketName, null), bucketName,
                        bucket.getCreationDate().getTime());
            }
        }
        return null;
    }

    @Override
    public Blob getObject(@Nullable String bucketName, @Nonnull String objectName)
            throws InternalException, CloudException {
        String regionId = getContext().getRegionId();
        if (regionId == null) {
            throw new InternalException("No region was set for this request");
        }

        ResponseHandler<ListBucketResult> responseHandler = new AliyunResponseHandler<ListBucketResult>(
                new XmlStreamToObjectProcessor(),
                ListBucketResult.class);

        String marker = null;
        while(true) {
            HttpUriRequest request = AliyunRequestBuilder.get()
                    .provider(getProvider())
                    .category(AliyunRequestBuilder.Category.OSS)
                    .subdomain(bucketName)
                    .parameter("marker", marker)
                    .parameter("prefix", objectName)
                    .build();

            ListBucketResult result = new AliyunRequestExecutor<ListBucketResult>(getProvider(),
                    AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                    request,
                    responseHandler).execute();

            for (ListBucketResult.Contents contents : result.getContentses()) {
                if(contents.getKey().equals(objectName)) {
                    Storage<org.dasein.util.uom.storage.Byte> size = new Storage<org.dasein.util.uom.storage.Byte>(
                            contents.getSize(), Storage.BYTE);
                    return Blob.getInstance(regionId, generateUrl(regionId, bucketName, objectName), bucketName, objectName,
                            contents.getLastModified().getTime(), size);
                }
            }

            if (result.isTruncated()) {
                marker = result.getContentses().get(result.getContentses().size() - 1).getKey();
            } else {
                break;
            }
        }

        return null;
    }

    @Nullable
    @Override
    public String getSignedObjectUrl(@Nonnull String bucket, @Nonnull String object,
            @Nonnull String expiresEpochInSeconds) throws InternalException, CloudException {
        String regionId = getContext().getRegionId();
        if (regionId == null) {
            throw new InternalException("No region was set for this request");
        }

        byte[][] accessKey = (byte[][]) getProvider().getContext().getConfigurationValue(Aliyun.DSN_ACCESS_KEY);
        byte[] accessKeyId = accessKey[0];
        byte[] accessKeySecret = accessKey[1];

        try {
            SecretKeySpec signingKey = new SecretKeySpec(accessKeySecret, AliyunRequestBuilderStrategy.SIGNATURE_ALGORITHM);
            Mac mac = Mac.getInstance(AliyunRequestBuilderStrategy.SIGNATURE_ALGORITHM);
            mac.init(signingKey);
            String data = "GET\n\n\n" + expiresEpochInSeconds + "\n/" + bucket + "/" + object;
            byte[] rawHmac = mac.doFinal(data.getBytes());
            String signature = URLEncoder.encode(new String(Base64.encodeBase64(rawHmac)), "UTF-8");

            String signedUrl = generateUrl(regionId, bucket, object) + "?OSSAccessKeyId=" +
                    new String(accessKeyId) + "&Signature=" + signature + "&Expires=" + expiresEpochInSeconds;
            return signedUrl;
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            stdLogger.error("Failed to create Mac", noSuchAlgorithmException);
            throw new InternalException("Failed to create Mac", noSuchAlgorithmException);
        } catch (InvalidKeyException invalidKeyException) {
            stdLogger.error("Failed to init Mac", invalidKeyException);
            throw new InternalException("Failed to init Mac", invalidKeyException);
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            stdLogger.error("Failed to encode to UTF-8", unsupportedEncodingException);
            throw new InternalException("Failed to encode to UTF-8", unsupportedEncodingException);
        }
    }

    @Nullable
    @Override
    public Storage<Byte> getObjectSize(@Nullable final String bucketName, @Nullable String objectName)
            throws InternalException, CloudException {
        return getObject(bucketName, objectName).getSize();
        /*
        final String regionId = getContext().getRegionId();
        if (regionId == null) {
            throw new InternalException("No region was set for this request");
        }

        HttpUriRequest request = AliyunRequestBuilder.head()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucketName)
                .path("/" + objectName)
                .build();
        ResponseHandler<Storage<Byte>> responseHandler = new ResponseHandler<Storage<Byte>>(){
            @Override
            public Storage<Byte> handleResponse(HttpResponse response) throws IOException {
                int httpCode = response.getStatusLine().getStatusCode();
                if(httpCode == HttpStatus.SC_OK ) {
                    Long size = Long.parseLong(response.getFirstHeader("Content-Length").getValue());
                    return new Storage<org.dasein.util.uom.storage.Byte>(size, Storage.BYTE);
                } else {
                    stdLogger.error("Unexpected OK for HEAD request, got " + httpCode);
                    throw new AliyunResponseException(httpCode, null, null,
                            response.getFirstHeader("x-oss-request-id").getValue(), generateHost(regionId, bucketName));
                }
            }
        };
        return new AliyunRequestExecutor<Storage<Byte>>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(), request, responseHandler).execute();
        */
    }

    @Override
    public boolean isPublic(@Nullable String bucket, @Nullable String object) throws CloudException, InternalException {
        HttpUriRequest request = AliyunRequestBuilder.get()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .parameter("acl", null)
                .build();

        ResponseHandler<AccessControlPolicy> responseHandler = new AliyunResponseHandler<AccessControlPolicy>(
                new XmlStreamToObjectProcessor(),
                AccessControlPolicy.class);

        AccessControlPolicy result = new AliyunRequestExecutor<AccessControlPolicy>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(), request, responseHandler).execute();

        String grant = result.getAccessControlList().getGrant();
        if ("public-read-write".equals(grant) || "public-read".equals(grant)) {
            return true;
        } else {
            return false;
        }
    }

    @Nonnull
    @Override
    public Iterable<Blob> list(@Nullable String bucket) throws CloudException, InternalException {
        String regionId = getContext().getRegionId();
        if (regionId == null) {
            throw new InternalException("No region was set for this request");
        }

        ResponseHandler<ListBucketResult> responseHandler = new AliyunResponseHandler<ListBucketResult>(
                new XmlStreamToObjectProcessor(),
                ListBucketResult.class);

        List<Blob> blobs = new ArrayList<Blob>();

        int maxKeys = 100; //default
        String marker = null;
        while(true) {
            HttpUriRequest request = AliyunRequestBuilder.get()
                    .provider(getProvider())
                    .category(AliyunRequestBuilder.Category.OSS)
                    .subdomain(bucket)
                    .parameter("marker", marker)
                    .parameter("max-keys", maxKeys)
                    .build();

            ListBucketResult result = new AliyunRequestExecutor<ListBucketResult>(getProvider(),
                    AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                    request,
                    responseHandler).execute();

            for (ListBucketResult.Contents contents : result.getContentses()) {
                String objectName = contents.getKey();
                Storage<org.dasein.util.uom.storage.Byte> size = new Storage<org.dasein.util.uom.storage.Byte>(
                        contents.getSize(), Storage.BYTE);
                blobs.add(Blob.getInstance(regionId, generateUrl(regionId, bucket, objectName), bucket, objectName,
                        contents.getLastModified().getTime(), size));
            }

            if (result.isTruncated()) {
                marker = result.getContentses().get(result.getContentses().size() - 1).getKey();
            } else {
                break;
            }
        }

        return blobs;
    }

    @Override
    public void makePublic(@Nonnull String bucket) throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.put()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .parameter("acl", null)
                .header("x-oss-acl", "public-read")
                .entity("", new StreamToStringProcessor()) //to force RequestBuilder put parameters in URL
                .build();

        new AliyunRequestExecutor<String>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                new AliyunValidateEmptyResponseHandler()).execute();
    }

    @Override
    public void makePublic(@Nullable String bucket, @Nonnull String object) throws InternalException, CloudException {
        throw new OperationNotSupportedException("Cannot make make a single object public");
    }

    @Override
    public void move(@Nullable String fromBucket, @Nullable String objectName, @Nullable String toBucket)
            throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Blob.move");
        try {
            if( fromBucket == null ) {
                throw new InternalException("No source bucket was specified");
            }
            if( toBucket == null ) {
                throw new InternalException("No target bucket was specified");
            }
            if( objectName == null ) {
                throw new InternalException("No source object was specified");
            }
            copy(fromBucket, objectName, toBucket, objectName);
            removeObject(fromBucket, objectName);
        } finally {
            APITrace.end();
        }
    }

    @Override
    public void removeBucket(@Nonnull String bucket) throws CloudException, InternalException {
        HttpUriRequest request = AliyunRequestBuilder.delete()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .build();

        new AliyunRequestExecutor<String>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                new AliyunValidateEmptyResponseHandler()).execute();
    }

    @Override
    public void removeObject(@Nullable String bucket, @Nonnull String object) throws CloudException, InternalException {
        HttpUriRequest request = AliyunRequestBuilder.delete()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .path("/" + object)
                .build();

        new AliyunRequestExecutor<String>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                request,
                new AliyunValidateEmptyResponseHandler()).execute();
    }

    @Nonnull
    @Override
    public String renameBucket(@Nonnull String oldName, @Nonnull String newName, boolean findFreeName)
            throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Blob.renameBucket");
        try {
            Blob bucket = createBucket(newName, findFreeName);

            for (Blob file : list(oldName)) {
                int retries = 10;

                while (true) {
                    retries--;
                    try {
                        move(oldName, file.getObjectName(), bucket.getBucketName());
                        break;
                    } catch (CloudException e) {
                        if (retries < 1) {
                            throw e;
                        }
                    }
                    try {
                        Thread.sleep(retries * 10000L);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
            boolean ok = true;
            for (Blob file : list(oldName)) {
                if (file != null) {
                    ok = false;
                }
            }
            if (ok) {
                removeBucket(oldName);
            }
            return newName;
        } finally {
            APITrace.end();
        }
    }

    private InitiateMultipartUploadResult initiateMultipartUpload(String bucket, String target)
            throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.post()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .path("/" + target)
                .parameter("uploads", null)
                .entity("", new StreamToStringProcessor()) //to force RequestBuilder put parameters in URL
                .build();

        ResponseHandler<InitiateMultipartUploadResult> responseHandler = new AliyunResponseHandler<InitiateMultipartUploadResult>(
                new XmlStreamToObjectProcessor(),
                InitiateMultipartUploadResult.class);

        return new AliyunRequestExecutor<InitiateMultipartUploadResult>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(), request, responseHandler).execute();
    }

    private CompleteMultipartUpload.Part copyMultipartUpload(InitiateMultipartUploadResult initiateMultipartUploadResult,
            String source, long firstByte, long lastByte, int partNumber) throws InternalException, CloudException {
        String bucket = initiateMultipartUploadResult.getBucket();
        String target = initiateMultipartUploadResult.getKey();
        String uploadId = initiateMultipartUploadResult.getUploadId();

        HttpUriRequest request = AliyunRequestBuilder.put()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .path("/" + target)
                .parameter("partNumber", partNumber)
                .parameter("uploadId", uploadId)
                .header("x-oss-copy-source", "/" + bucket + "/" + source)
                .header("x-oss-copy-source-range", "bytes=" + firstByte + "-" + lastByte)
                .entity("", new StreamToStringProcessor()) //to force RequestBuilder put parameters in URL
                .build();

        ResponseHandler<CopyPartResult> responseHandler = new AliyunResponseHandler<CopyPartResult>(
                new XmlStreamToObjectProcessor(),
                CopyPartResult.class);

        CopyPartResult copyObjectResult = new AliyunRequestExecutor<CopyPartResult>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(), request, responseHandler).execute();

        CompleteMultipartUpload.Part part = new CompleteMultipartUpload.Part();
        part.setETag(copyObjectResult.getETag());
        part.setPartNumber(partNumber);
        return part;
    }

    private CompleteMultipartUploadResult completeMultipartUpload(String bucket, String target, String uploadId,
            CompleteMultipartUpload completeMultipartUpload) throws InternalException, CloudException {
        HttpUriRequest request = AliyunRequestBuilder.post()
                .provider(getProvider())
                .category(AliyunRequestBuilder.Category.OSS)
                .subdomain(bucket)
                .path("/" + target)
                .parameter("uploadId", uploadId)
                .entity(completeMultipartUpload, new XmlStreamToObjectProcessor<CompleteMultipartUpload>())
                .build();

        ResponseHandler<CompleteMultipartUploadResult> responseHandler = new AliyunResponseHandler<CompleteMultipartUploadResult>(
                new XmlStreamToObjectProcessor(),
                CompleteMultipartUploadResult.class);

        return new AliyunRequestExecutor<CompleteMultipartUploadResult>(getProvider(),
                AliyunHttpClientBuilderFactory.newHttpClientBuilder(), request, responseHandler).execute();
    }

    @Override
    public void renameObject(@Nullable String bucket, @Nonnull String oldName, @Nonnull String newName)
            throws CloudException, InternalException {
        Storage<Byte> size = getObjectSize(bucket, oldName);
        if (size.convertTo(Storage.GIGABYTE).doubleValue() < MULTIPART_UPLOAD_COPY_THRESHOLD_IN_GB) {
            HttpUriRequest request = AliyunRequestBuilder.put()
                    .provider(getProvider())
                    .category(AliyunRequestBuilder.Category.OSS)
                    .subdomain(bucket)
                    .path("/" + newName)
                    .header("x-oss-copy-source", "/" + bucket + "/" + oldName)
                    .entity("", new StreamToStringProcessor()) //to force RequestBuilder put parameters in URL
                    .build();

            ResponseHandler<CopyObjectResult> responseHandler = new AliyunResponseHandler<CopyObjectResult>(
                    new XmlStreamToObjectProcessor(),
                    CopyObjectResult.class);

            new AliyunRequestExecutor<CopyObjectResult>(getProvider(),
                    AliyunHttpClientBuilderFactory.newHttpClientBuilder(),
                    request,
                    responseHandler).execute();
        } else {
            long partSize = new Storage<Megabyte>(MULTIPART_UPLOAD_COPY_PART_SIZE_IN_MB, Storage.MEGABYTE)
                    .convertTo(Storage.BYTE).longValue();
            List<CompleteMultipartUpload.Part> parts = new ArrayList<CompleteMultipartUpload.Part>();

            InitiateMultipartUploadResult initiateMultipartUploadResult = initiateMultipartUpload(bucket, newName);
            int partNumber = 1;
            long firstByte = 0;
            long lastByte = partSize;
            while(true) {
                parts.add(copyMultipartUpload(initiateMultipartUploadResult, oldName, firstByte, lastByte, partNumber));
                firstByte = lastByte + 1;
                if(firstByte >= size.longValue()) {
                    break;
                }

                lastByte = firstByte + partSize;
                if (lastByte >= size.longValue()) {
                    lastByte = size.longValue() - 1;
                }

                partNumber = partNumber + 1;
            }

            CompleteMultipartUpload completeMultipartUpload = new CompleteMultipartUpload();
            completeMultipartUpload.setParts(parts);
            completeMultipartUpload(bucket, newName, initiateMultipartUploadResult.getUploadId(), completeMultipartUpload);
        }
        removeObject(bucket, oldName);
    }

    @Nonnull
    @Override
    public Blob upload(@Nonnull File sourceFile, @Nullable String bucket, @Nonnull String objectName)
            throws CloudException, InternalException {
        put(bucket, objectName, sourceFile);
        return getObject(bucket, objectName);
    }

    @Nonnull
    @Override
    public String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];
    }
}
