package io.siggi.databackup.data.content;

import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.util.stream.ZeroFilledInputStream;

import java.io.InputStream;

public final class ZeroFilledFileContent extends FileContent {

    @Override
    public int getTypeId() {
        return FileContent.TYPE_ZERO_FILLED;
    }

    public InputStream getInputStream(DataRepository repository) {
        return new ZeroFilledInputStream(getLength());
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) return false;
        if (other == this) return true;
        return other instanceof ZeroFilledFileContent;
    }

    @Override
    public String toString() {
        return "zerofilled:" + getLength();
    }
}
