package com.crosschecklab.domain.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// real-extraction 프로파일 전용. 원본은 SHA-256 기반 경로에 영구 보존하며 읽어도 삭제하지 않는다.
@Component
@Profile("real-extraction")
public class DurableLocalFileStorage implements FileStorage {

    public static final String STORAGE_KEY_PREFIX = "sha256://documents/";

    private static final int BUFFER_SIZE = 8192;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final String STAGING_DIRECTORY = ".staging";

    private final Path storageRoot;

    public DurableLocalFileStorage(
            @Value("${document.storage.root:/var/lib/argus/documents}") String storageRoot) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(MultipartFile file, String fixtureKey) {
        Path temporaryFile = null;
        try {
            Path stagingDirectory = storageRoot.resolve(STAGING_DIRECTORY);
            createPrivateDirectory(storageRoot);
            createPrivateDirectory(stagingDirectory);
            temporaryFile = Files.createTempFile(stagingDirectory, "upload-", ".tmp");
            setPrivateFilePermissions(temporaryFile, false);

            MessageDigest digest = FileDigest.newDigest();
            long size = copyAndDigest(file, temporaryFile, digest);
            String checksum = FileDigest.toHex(digest.digest());
            Path destinationDirectory = storageRoot.resolve(checksum.substring(0, 2));
            createPrivateDirectory(destinationDirectory);
            Path destination = destinationDirectory.resolve(checksum);

            promoteAtomically(temporaryFile, destination);
            temporaryFile = null;
            setPrivateFilePermissions(destination, true);

            return new StoredFile(STORAGE_KEY_PREFIX + checksum, checksum, size);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 원본을 영구 저장하지 못했습니다.", e);
        } finally {
            deleteTemporaryFile(temporaryFile);
        }
    }

    @Override
    public Optional<byte[]> read(String storageKey) {
        String checksum = checksumOf(storageKey);
        if (checksum == null) {
            return Optional.empty();
        }

        Path source = storageRoot.resolve(checksum.substring(0, 2)).resolve(checksum);
        try {
            if (!Files.isRegularFile(source)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(source));
        } catch (IOException e) {
            throw new UncheckedIOException("저장된 업로드 원본을 읽지 못했습니다: " + storageKey, e);
        }
    }

    private long copyAndDigest(MultipartFile file, Path temporaryFile, MessageDigest digest)
            throws IOException {
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(
                     temporaryFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return size;
    }

    private void promoteAtomically(Path temporaryFile, Path destination) throws IOException {
        if (Files.exists(destination)) {
            Files.delete(temporaryFile);
            return;
        }

        try {
            Files.move(temporaryFile, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(temporaryFile);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("저장소가 원자적 파일 승격을 지원하지 않습니다.", e);
        }
    }

    private String checksumOf(String storageKey) {
        if (storageKey == null || !storageKey.startsWith(STORAGE_KEY_PREFIX)) {
            return null;
        }
        String checksum = storageKey.substring(STORAGE_KEY_PREFIX.length());
        return SHA_256.matcher(checksum).matches() ? checksum : null;
    }

    private void createPrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private void setPrivateFilePermissions(Path file, boolean readOnly) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(
                    file, PosixFilePermissions.fromString(readOnly ? "r--------" : "rw-------"));
        }
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // 원래 저장 실패를 보존한다. 임시 디렉터리는 외부에 노출되지 않는다.
        }
    }
}
