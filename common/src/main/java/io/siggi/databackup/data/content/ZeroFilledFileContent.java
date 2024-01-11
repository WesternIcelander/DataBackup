package io.siggi.databackup.data.content;

public final class ZeroFilledFileContent extends FileContent {

    @Override
    public int getTypeId() {
        return FileContent.TYPE_ZERO_FILLED;
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
