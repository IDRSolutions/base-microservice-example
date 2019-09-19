/*
 * Base Microservice Example
 *
 * Project Info: https://github.com/idrsolutions/base-microservice-example
 *
 * Copyright 2018 IDRsolutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package conversion;

import conversion.utils.DownloadHelper;
import conversion.utils.HttpHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.SizeLimitExceededException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.servlet.ServletException;

/**
 * An extendable base for conversion microservices. Provides general
 * functionality for polling, file upload/download, initial creation of files
 * and UUID's.
 */
public abstract class BaseServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(BaseServlet.class.getName());

    private static String INPUTPATH = "../docroot/input/";
    private static String OUTPUTPATH = "../docroot/output/";
    private static long individualTTL = 86400000L; // 24 hours

    private static final int NUM_DOWNLOAD_RETRIES = 2;
    private static final int PCOUNT = Runtime.getRuntime().availableProcessors();

    private final ConcurrentHashMap<String, Individual> imap = new ConcurrentHashMap<>();

    private final ExecutorService convertQueue = Executors.newFixedThreadPool(PCOUNT);
    private final ExecutorService downloadQueue = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService callbackQueue = Executors.newScheduledThreadPool(5);
    
    /**
     * Get the location where input files is stored
     * @return inputPath the path where input files is stored
     */
    public static String getInputPath() {
        return INPUTPATH;
    }
    
    /**
     * Get the location where the converter output is stored
     * 
     * @return outputPath the path where output files is stored
     */
    public static String getOutputPath() {
        return OUTPUTPATH;
    }
    
    /**
     * Get the time to live of individuals on the server (The duration that the 
     * information of an individual is kept on the server)
     * 
     * @return individualTTL the time to live of an individual
     */
    public static long getIndividualTTL() {
        return individualTTL;
    }
    
    /**
     * Set the location where input files is stored
     * 
     * @param inputPath the path where input files is stored
     */
    public static void setInputPath(final String inputPath) {
        INPUTPATH = inputPath;
    }
    
    /**
     * Set the location where the converter output is stored
     * 
     * @param outputPath the path where output files is stored
     */
    public static void setOutputPath(final String outputPath) {
        OUTPUTPATH = outputPath;
    }
    
    /**
     * Set the time to live of individuals on the server (The duration that the 
     * information of an individual is kept on the server)
     * 
     * @param ttlDuration the time to live of an individual
     */
    public static void setIndividualTTL(final long ttlDuration) {
        individualTTL = ttlDuration;
    }
    
    /**
     * Set an HTTP error code and message to the given response.
     *
     * @param response the HttpServletResponse object on which to send the
     * response
     * @param error the error message to pass in the body of the client
     * @param status the HTTP status to set the response to response.
     */
    protected static void doError(final HttpServletResponse response, final String error, final int status) {
        response.setStatus(status);
        sendResponse(response, "{\"error\":\"" + error + "\"}");
    }

    /**
     * Send a JSON response
     *
     * @param response the HttpServletResponse object on which to send the
     * response
     * @param content the JSON response to send
     */
    private static void sendResponse(final HttpServletResponse response, final String content) {
        allowCrossOrigin(response);
        response.setContentType("application/json");
        try (final PrintWriter out = response.getWriter()) {
            out.println(content);
        } catch (final IOException e) {
            LOG.log(Level.SEVERE, "IOException thrown when sending json response", e);
        }
    }

    /**
     * Get request to the servlet. See API docs in respective end servlets for
     * more information.
     *
     * @param request the request from the client
     * @param response the response to send once this method exits
     * @see Individual#toJsonString()
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        final String uuidStr = request.getParameter("uuid");
        if (uuidStr == null) {
            doError(response, "No uuid provided", 404);
            return;
        }

        final Individual individual = imap.get(uuidStr);
        if (individual == null) {
            doError(response, "Unknown uuid: " + uuidStr, 404);
            return;
        }

        sendResponse(response, individual.toJsonString());
    }

    /**
     * Writes to response object with the communication methods that this server
     * supports.
     *
     * @param request the request from the client
     * @param response the response to send once this method exits
     * @see BaseServlet#allowCrossOrigin(HttpServletResponse)
     */
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) {
        allowCrossOrigin(response);
    }

    /**
     * Allow cross origin requests according to the CORS standard.
     *
     * @param response the response object to the request from the client
     */
    private static void allowCrossOrigin(final HttpServletResponse response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS, DELETE");
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Access-Control-Allow-Origin");
    }

    /**
     * A post request to the server.
     *
     * @param request the request from the client
     * @param response the response to send once this method exits
     * @see BaseServlet#convert(Individual, Map, File, File, String)
     */
    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response) {

        imap.entrySet().removeIf(entry -> entry.getValue().getTimestamp() < new Date().getTime() - individualTTL);

        final String inputType = request.getParameter("input");
        if (inputType == null) {
            doError(response, "Missing input type", 400);
            return;
        }

        final String uuidStr = UUID.randomUUID().toString();
        final Individual individual = new Individual(uuidStr);

        individual.setCustomData(request.getAttribute("customData"));

        switch (inputType) {
            case "upload":
                if (!handleFileFromRequest(individual, request, response)) {
                    return;
                }
                break;

            case "download":
                if (!handleFileFromUrl(individual, request, response)) {
                    return;
                }
                break;

            default:
                doError(response, "Unrecognised input type", 400);
                return;
        }

        imap.put(uuidStr, individual);

        sendResponse(response, "{" + "\"uuid\":\"" + uuidStr + "\"}");
    }

    /**
     * Sanitize the file name by removing all none url/filepath friendly
     * characters.
     *
     * @param fileName the filename to sanitize
     * @return the sanitized filename
     */
    private static String sanitizeFileName(final String fileName) {
        final int extPos = fileName.lastIndexOf('.');
        // Limit filenames to chars allowed in unencoded URLs and Windows filenames for now
        final String fileNameWithoutExt = fileName.substring(0, extPos).replaceAll("[^$\\-_.+!'(),a-zA-Z0-9]", "_");
        final String ext = fileName.substring(extPos + 1);

        return fileNameWithoutExt + '.' + ext;
    }

    /**
     * Create the input directory for the clients file.
     *
     * @param uuid the uuid to use to create the directory
     * @return the input directory
     */
    private static File createInputDirectory(final String uuid) {
        final String userInputDirPath = INPUTPATH + uuid;
        final File inputDir = new File(userInputDirPath);
        if (!inputDir.exists()) {
            inputDir.mkdirs();
        }
        return inputDir;
    }

    /**
     * Create the output directory to store the converted pdf at.
     *
     * @param uuid the uuid to use to create the output directory
     * @return the output directory
     */
    private static File createOutputDirectory(final String uuid) {
        final String userOutputDirPath = OUTPUTPATH + uuid;
        final File outputDir = new File(userOutputDirPath);
        if (outputDir.exists()) {
            deleteFolder(outputDir);
        }
        outputDir.mkdirs();
        return outputDir;
    }

    /**
     * Handle and convert file uploaded in the request.
     * <p>
     * This method blocks until the file is initially processed and exists when
     * the conversion begins.
     *
     * @param individual the individual associated with this conversion
     * @param request the request for this conversion
     * @param response the response object for the request
     * @return true on success, false on failure
     */
    private boolean handleFileFromRequest(final Individual individual, final HttpServletRequest request, final HttpServletResponse response) {
        final Part filePart;
        try {
            filePart = request.getPart("file");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IOException when getting the file part", e);
            doError(response, "Error handling file", 500);
            return false;
        } catch (ServletException e) {
            doError(response, "Missing file", 400);
            return false;
        }

        if (filePart == null) {
            doError(response, "Missing file", 400);
            return false;
        }

        final long fileSizeLimit = getFileSizeLimit(request);
        if (fileSizeLimit > 0 && filePart.getSize() > fileSizeLimit) {
            doError(response, "File size limit exceeded", 400);
            return false;
        }

        final String originalFileName = getFileName(filePart);
        if (originalFileName == null) {
            doError(response, "Missing file name", 400);
            return false;
        }

        final File inputFile;
        try {
            final InputStream fileContent = filePart.getInputStream();
            final byte[] fileBytes = new byte[(int) filePart.getSize()];
            fileContent.read(fileBytes);
            fileContent.close();
            inputFile = outputFile(originalFileName, individual, fileBytes);
        } catch (final IOException e) {
            LOG.log(Level.SEVERE, "IOException when reading an uploaded file", e);
            doError(response, "Internal error", 500); // Failed to save file to disk
            return false;
        }

        final File outputDir = createOutputDirectory(individual.getUuid());

        addToQueue(individual, request.getParameterMap(), inputFile, outputDir, getContextURL(request));

        return true;
    }

    /**
     * Handle and convert a file located at a given url.
     * <p>
     * This method does not block when attempting to download the file from the
     * url.
     *
     * @param individual the individual associated with this conversion
     * @param request the request for this conversion
     * @param response the response object for the request
     * @return true on initial success (url has been provided)
     */
    private boolean handleFileFromUrl(final Individual individual, final HttpServletRequest request, final HttpServletResponse response) {

        String url = request.getParameter("url");
        if (url == null) {
            doError(response, "No url given", 400);
            return false;
        }
        // This does not need to be asynchronous
        String filename = DownloadHelper.getFileNameFromUrl(url);
        // In case a filename cannot be parsed from the url.
        if (filename == null) {
            filename = "document.pdf";
        }

        final long fileSizeLimit = getFileSizeLimit(request);
        if (fileSizeLimit > 0) {
            long fileSize;
            try {
                fileSize = DownloadHelper.getFileSizeFromUrl(url);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "IOException when finding the FileSize of a remote file", e);
                doError(response, "Internal error", 500);
                return false;
            }

            if (fileSize > fileSizeLimit) {
                doError(response, "File size limit exceeded", 400);
                return false;
            }
        }

        // To allow use in lambda function.
        final String finalFilename = filename;
        final String contextUrl = getContextURL(request);
        final Map<String, String[]> parameterMap = request.getParameterMap();

        downloadQueue.submit(() -> {
            File inputFile = null;
            try {
                final byte[] fileBytes = DownloadHelper.getFileFromUrl(url, NUM_DOWNLOAD_RETRIES, fileSizeLimit);
                inputFile = outputFile(finalFilename, individual, fileBytes);
            } catch (IOException e) {
                individual.doError(1200);
            } catch (SizeLimitExceededException e) {
                individual.doError(1210);
            }

            final File outputDir = createOutputDirectory(individual.getUuid());
            addToQueue(individual, parameterMap, inputFile, outputDir, contextUrl);
        });

        return true;
    }

    /**
     * Add a conversion task to the thread queue.
     *
     * @param individual the individual belonging to this conversion
     * @param params the parameter map from the request
     * @param inputFile the input file to convert
     * @param outputDir the output directory to convert to
     * @param contextUrl the context url of the servlet
     */
    private void addToQueue(final Individual individual, final Map<String, String[]> params, final File inputFile,
            final File outputDir, final String contextUrl) {
        final Map<String, String[]> paramsCopy = new HashMap<>(params);

        convertQueue.submit(() -> {
            try {
                convert(individual, paramsCopy, inputFile, outputDir, contextUrl);
                handleCallback(individual, paramsCopy);
            } finally {
                individual.setAlive(false);
            }
        });
    }

    /**
     * This method converts a file and writes it to the output directory under
     * the Individual's UUID.
     *
     * @param individual Internal representation of individual who made this
     * request
     * @param params the map of parameters from the request
     * @param inputFile the File to convert
     * @param outputDir the directory the converted file should be written to
     * @param contextUrl The url from the protocol up to the servlet url
     * pattern.
     */
    protected abstract void convert(Individual individual, Map<String, String[]> params,
            File inputFile, File outputDir, String contextUrl);

    /**
     * Write the given file bytes to the output directory under filename.
     *
     * @param filename the filename to output to
     * @param individual the individual that began the conversion request
     * @param fileBytes the bytes to be written.
     * @return the created file
     * @throws IOException on file not being writable
     */
    private File outputFile(String filename, Individual individual, byte[] fileBytes) throws IOException {
        final File inputDir = createInputDirectory(individual.getUuid());
        final File inputFile = new File(inputDir, sanitizeFileName(filename));

        try (final FileOutputStream output = new FileOutputStream(inputFile)) {
            output.write(fileBytes);
            output.flush();
        }

        return inputFile;
    }

    /**
     * Get the filename of the file contained in this request part.
     *
     * @param part the file part from the HTTP request
     * @return the file name or null if it does not exist
     */
    private String getFileName(final Part part) {
        for (String content : part.getHeader("content-disposition").split(";")) {
            if (content.trim().startsWith("filename")) {
                return content.substring(
                        content.indexOf('=') + 1).trim().replace("\"", "");
            }
        }
        return null;
    }

    /**
     * Checks if the callbackUrl parameter was included in the request, if so it
     * will queue the callback into the callbackQueue.
     *
     * @param individual the Individual that is sent to the URL
     * @param params the request parameters
     */
    private void handleCallback(final Individual individual, final Map<String, String[]> params) {
        final String[] rawParam = params.get("callbackUrl");

        if (rawParam != null && rawParam.length > 0) {
            final String callbackUrl = rawParam[0];

            if (!callbackUrl.equals("")) {
                callbackQueue.submit(() -> HttpHelper.sendCallback(callbackUrl, individual.toJsonString(), callbackQueue, 1));
            }
        }
    }

    /**
     * Gets the full URL before the part containing the path(s) specified in
     * urlPatterns of the servlet.
     *
     * @param request the request from the client
     * @return protocol://servername/contextPath
     */
    protected static String getContextURL(final HttpServletRequest request) {
        final StringBuffer full = request.getRequestURL();
        return full.substring(0, full.length() - request.getServletPath().length());
    }

    /**
     * Try to get the fileSizeLimit attribute as a long from the
     * HttpServeletRequest
     *
     * @param request the request from the client
     * @return the value of fileSizeLimit or -1 if the attribute is not set
     */
    private static long getFileSizeLimit(final HttpServletRequest request) {
        final Object rawSizeLimit = request.getAttribute("fileSizeLimit");
        if (rawSizeLimit != null && rawSizeLimit instanceof Long) {
            return (long) rawSizeLimit;
        } else {
            return -1L;
        }
    }

    /**
     * Get the conversion parameters.
     *
     * @param settings a list of k/v pairs in the form:
     * "key1:val1;key2:val2;etc..."
     * @return a String array in the form [key1, val1, key2, val2, etc...]
     */
    protected static String[] getConversionParams(final String settings) {
        if (settings == null) {
            return null;
        }
        final String[] splits = settings.split(";");
        final String[] result = new String[splits.length * 2];
        int p = 0;
        for (final String set : splits) {
            final String[] ss = set.split(":");
            if (ss.length < 2 && ss.length % 2 != 0) {
                return null;
            }
            result[p++] = ss[0];
            result[p++] = ss[1];
        }
        return result;
    }

    /**
     * Delete a folder and all of its contents.
     *
     * @param dirPath the path to the folder to delete
     */
    private static void deleteFolder(final File dirPath) {
        final File[] files = dirPath.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                }
                file.delete();
            }
        }
    }
}
