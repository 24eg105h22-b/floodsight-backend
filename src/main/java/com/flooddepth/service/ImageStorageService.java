package com.flooddepth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ImageStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final Path uploadDirectory;

    public ImageStorageService(@Value("${app.upload-dir:uploads}") String uploadDirectory) {
        this.uploadDirectory = Paths.get(uploadDirectory).toAbsolutePath().normalize();

        try {
            Files.createDirectories(this.uploadDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize the image upload directory", exception);
        }
    }

    public StoredImage storeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file must not be empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("Image file must have a filename");
        }

        String sanitizedFilename = StringUtils.cleanPath(originalFilename);
        if (sanitizedFilename.contains("..")
                || sanitizedFilename.contains("/")
                || sanitizedFilename.contains("\\")) {
            throw new IllegalArgumentException("Invalid image filename");
        }

        String extension = getExtension(sanitizedFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only JPG, JPEG, and PNG images are allowed");
        }

        String generatedFilename = UUID.randomUUID() + "." + extension;
        Path destination = uploadDirectory.resolve(generatedFilename).normalize();

        if (!destination.getParent().equals(uploadDirectory)) {
            throw new IllegalArgumentException("Invalid image filename");
        }

        try {
            Files.copy(file.getInputStream(), destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store the image file", exception);
        }

        return new StoredImage(generatedFilename, "uploads/" + generatedFilename);
    }

    public StoredImageFile loadStoredImage(String imagePath) {
        if (!StringUtils.hasText(imagePath) || !imagePath.startsWith("uploads/")) {
            throw new IllegalArgumentException("A valid uploaded image path is required");
        }

        String filename = imagePath.substring("uploads/".length());
        String sanitizedFilename = StringUtils.cleanPath(filename);
        if (!StringUtils.hasText(sanitizedFilename)
                || !sanitizedFilename.equals(filename)
                || sanitizedFilename.contains("..")
                || sanitizedFilename.contains("/")
                || sanitizedFilename.contains("\\")) {
            throw new IllegalArgumentException("Invalid uploaded image path");
        }

        String extension = getExtension(sanitizedFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Uploaded image must be JPG, JPEG, or PNG");
        }

        Path storedFile = uploadDirectory.resolve(sanitizedFilename).normalize();
        if (!storedFile.getParent().equals(uploadDirectory)) {
            throw new IllegalArgumentException("Invalid uploaded image path");
        }
        if (!Files.isRegularFile(storedFile)) {
            throw new IllegalArgumentException("Uploaded image was not found");
        }

        String mediaType = extension.equals("png") ? "image/png" : "image/jpeg";
        return new StoredImageFile(storedFile, mediaType);
    }

    private String getExtension(String filename) {
        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator <= 0 || extensionSeparator == filename.length() - 1) {
            return "";
        }

        return filename.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT);
    }

    public record StoredImage(String fileName, String imagePath) {
    }

    public record StoredImageFile(Path path, String mediaType) {
    }
}
