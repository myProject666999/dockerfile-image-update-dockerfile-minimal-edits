package com.salesforce.dockerfileimageupdate.parser;

/**
 * Thrown when a Dockerfile cannot be handled without transcoding or guessing at its encoding.
 * Callers must leave the file byte-for-byte untouched when they see this.
 */
public class UnsupportedDockerfileEncodingException extends Exception {
    public UnsupportedDockerfileEncodingException(String message) {
        super(message);
    }
}
