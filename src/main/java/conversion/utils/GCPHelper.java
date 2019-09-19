/*
 * Copyright (c) 1997-2019 IDRsolutions (https://www.idrsolutions.com)
 */
package conversion.utils;

import conversion.Individual;

import com.google.api.gax.paging.Page;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static collection of methods to help with uploading to the Google Cloud 
 * Platform using the google-cloud-storage Java library
 */
public class GCPHelper {
    
    private final static Logger LOG = Logger.getLogger(GCPHelper.class.getName());
    
    /**
     * Handles the process of uploading to the Google Cloud Platform using the
     * google-cloud-storage Java library.
     * 
     * outputOptions can be set to the following keys:
     * bucketName - The name of the bucket that should be used. If not provided
     * the first bucket for the storage instance will be used.
     * gcpApplicationCredentials - The GCP credentials in json form. See 
     * https://cloud.google.com/docs/authentication/getting-started for info.
     * If not provided, the default instance will be used.
     * 
     * @param individual the individual object associated with this conversion
     * @param fileLocation the location of the file to be uploaded
     * @param fileContentType the content type of the file to be uploaded
     * @param outputOptions a map of the custom output options
     * @throws Exception when there is an issue loading the storage bucket or
     * when uploading the output file
     */
    public static void handleGCPUpload(final Individual individual, final String fileLocation, final String fileContentType, final Map<String, String> outputOptions) throws Exception {
        final Storage storage;
        final String gcpAC = outputOptions.get("gcpApplicationCredentials");
        final String bucketName = outputOptions.get("bucketName");
        
        if (gcpAC != null) {
            final ByteArrayInputStream gcpACStream = new ByteArrayInputStream(gcpAC.getBytes());
            final GoogleCredentials credentials = GoogleCredentials.fromStream(gcpACStream).createScoped("https://www.googleapis.com/auth/cloud-platform");
            storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
        } else {
            storage = StorageOptions.getDefaultInstance().getService();
        }
        
        final Page<Bucket> buckets = storage.list();
        final Iterator<Bucket> bi = buckets.getValues().iterator();
        
        if (bucketName != null) {
            while (bi.hasNext()) {
                final Bucket bucket = bi.next();
                if (bucket.getName().equals(bucketName)) {
                    uploadToGCP(individual, bucket, fileLocation, fileContentType);
                    return;
                }
            }
            throw new Exception("Unable to find a match to the requested bucket named " + bucketName + " on GCP");
        } else if (bi != null && bi.hasNext()) {
            final Bucket bucket = bi.next();
            uploadToGCP(individual, bucket, fileLocation, fileContentType);
        } else {
            throw new Exception("Unable to load a bucket on GCP");
        }
    }

    /**
     * Uploads the file provided to the GCP storage bucket provided and updates
     * the individual with the information required.
     * 
     * Values set for the individual object are:
     * blob = which returns the name of the created blob
     * downloadUrl - which returns the media link for the created blob
     * 
     * @param individual the individual object associated with this conversion
     * @param bucket the GCP storage bucket where the file will be stored
     * @param fileLocation the location of the file to be uploaded
     * @param fileContentType the content type of the file to be uploaded
     * @throws Exception when there is an issue uploading the file
     */
    public static void uploadToGCP(final Individual individual, final Bucket bucket, final String fileLocation, final String fileContentType) throws Exception {
        final Storage storage = bucket.getStorage();
        final BlobInfo blobInfo = BlobInfo.newBuilder(bucket.getName(), fileLocation).setContentType(fileContentType).build();
        final Blob blob = storage.create(blobInfo, Storage.BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.PUBLIC_READ));

        if (blob != null) {
            try (WriteChannel writer = storage.writer(blobInfo)) {
                individual.setState("uploading ouput");
                try {
                    final RandomAccessFile file = new RandomAccessFile(fileLocation, "r");
                    final FileChannel inChannel = file.getChannel();
                    final MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size());
                    for (int i = 0; i < buffer.limit(); i++) {
                        writer.write(buffer);
                    }

                    individual.setValue("blob", blob.getName());
                    individual.setValue("downloadUrl", blob.getMediaLink());
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, "IOException thrown when uploading file", ex);
                    throw ex;
                }
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Exception thrown when uploading file", ex);
                throw ex;
            }
        } else {
           throw new Exception("Cannot create blob on GCP");
        }
    }
}
