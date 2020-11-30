import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.common.base.Charsets;
import org.apache.commons.io.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AliceReadUploader {
    private static final Executor threadPool = Executors.newFixedThreadPool(8);
    private static final int BOOK_FILE_PART_LEN = 1024 - " Книга закончилась! Нужно закачать новую.".length();
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String CREDENTIALS = "google creds here";
    private static final String PARENT_FOLDER = "google-parent folder here";

    public static void main(String[] args) throws GeneralSecurityException, IOException {

        if (args.length < 3) {
            System.out.println("Usage: <path> <book name> <book author>");
            return;
        }
        String filePath = args[0];
        String bookName = args[1];
        String bookAuthor = args[2];

        Drive service = createDriveService();

        String fileContent = FileUtils
                .readFileToString(new java.io.File(filePath), "utf-8")
                .replaceAll("\\s{2}", "\n");
        int partStartPosition = 0;
        int partEndPosition = getPartEndPosition(fileContent, partStartPosition);
        int partsNumber = 1;

        while (true) {
            String filePartContent = fileContent.substring(partStartPosition, partEndPosition + 1);
            createFile(service, partsNumber + ".txt", filePartContent);
            partStartPosition = partEndPosition + 1;
            if (partEndPosition + BOOK_FILE_PART_LEN >= fileContent.length()) {
                partEndPosition = fileContent.length();
            } else {
                partEndPosition = getPartEndPosition(fileContent, partStartPosition);
            }
            partsNumber++;

            if (partEndPosition == fileContent.length()) {
                String lastPartContent = fileContent.substring(partStartPosition, partEndPosition);
                createFile(service, partsNumber + ".txt", lastPartContent);
                break;
            }
        }

        String statusFileContent = String.format(
                "{\"current\": 1,\"total\": %d,\"name\":\"%s\",\"author\":\"%s\"}",
                partsNumber,
                bookName,
                bookAuthor);
        System.out.println(statusFileContent);
        createFile(service, "status.json", statusFileContent);
    }

    private static int getPartEndPosition(String fileContent, int partStartPosition) {
        int partEndPosition;
        partEndPosition = partStartPosition + BOOK_FILE_PART_LEN;
        if ('.' != fileContent.charAt(partEndPosition) && '\n' != fileContent.charAt(partEndPosition)) {
            int pointPosition = fileContent.lastIndexOf('.', partEndPosition);
            int newLinePosition = fileContent.lastIndexOf('\n', partEndPosition);
            partEndPosition = Math.max(pointPosition, newLinePosition);
        }
        return partEndPosition;
    }

    private static Drive createDriveService() throws GeneralSecurityException, IOException {
        final NetHttpTransport driveHttpTransport = GoogleNetHttpTransport.newTrustedTransport();
        return new Drive.Builder(driveHttpTransport, JSON_FACTORY, getCredentials(driveHttpTransport))
                .setApplicationName("AliceReadUploader")
                .build();
    }

    private static void createFile(Drive service, String fileName, String content) throws IOException {
        File fileMetadata = new File();
        fileMetadata.setName(fileName);
        fileMetadata.setParents(Collections.singletonList(PARENT_FOLDER));

        threadPool.execute(() -> {
            AbstractInputStreamContent uploadStreamContent = new ByteArrayContent("text/plain", content.getBytes(Charsets.UTF_8));
            try {
                service
                        .files()
                        .create(fileMetadata, uploadStreamContent)
                        .setFields("id, webContentLink, webViewLink, parents")
                        .execute();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        InputStream in = new ByteArrayInputStream(CREDENTIALS.getBytes());
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, Collections.singletonList("https://www.googleapis.com/auth/drive"))
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File("tokens")))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }
}
