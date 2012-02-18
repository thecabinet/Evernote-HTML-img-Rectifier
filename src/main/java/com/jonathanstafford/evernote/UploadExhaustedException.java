package com.jonathanstafford.evernote;

public class UploadExhaustedException extends Exception {

    public UploadExhaustedException(long totalUpload, long uploaded, long noteSize, long reservedUpload) {
        super(String.format("rectified note is %,d bytes, which uses %,d bytes of the reserved upload",
                noteSize, reservedUpload - (totalUpload - uploaded - noteSize)));
    }
}
