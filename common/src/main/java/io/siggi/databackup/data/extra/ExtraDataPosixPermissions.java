package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.stream.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public final class ExtraDataPosixPermissions extends ExtraData {

    public ExtraDataPosixPermissions(int permissions, long owner, long group) {
        this.permissions = permissions;
        this.owner = owner;
        this.group = group;
    }

    public final int permissions;
    public final long owner;
    public final long group;

    public static ExtraDataPosixPermissions deserialize(byte[] data) {
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            int permissions = IO.readShort(in);
            long owner = IO.readLong(in);
            long group = IO.readLong(in);
            return new ExtraDataPosixPermissions(permissions, owner, group);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(18);
        try {
            IO.writeShort(out, permissions);
            IO.writeLong(out, owner);
            IO.writeLong(out, group);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataPosixPermissions o)) return false;
        return permissions == o.permissions && owner == o.owner && group == o.group;
    }

    @Override
    public int hashCode() {
        return Objects.hash(permissions, owner, group);
    }

    public String getPermissionsAsString() {
        StringBuilder sb = new StringBuilder();
        boolean setuid = (permissions & 04000) != 0;
        boolean setgid = (permissions & 02000) != 0;
        boolean sticky = (permissions & 01000) != 0;
        sb.append((permissions & 0400) != 0 ? "r" : "-");
        sb.append((permissions & 0200) != 0 ? "w" : "-");
        sb.append((permissions & 0100) != 0 ? (setuid ? "s" : "x") : (setuid ? "S" : "-"));
        sb.append((permissions & 040) != 0 ? "r" : "-");
        sb.append((permissions & 020) != 0 ? "w" : "-");
        sb.append((permissions & 010) != 0 ? (setgid ? "s" : "x") : (setgid ? "S" : "-"));
        sb.append((permissions & 04) != 0 ? "r" : "-");
        sb.append((permissions & 02) != 0 ? "w" : "-");
        sb.append((permissions & 01) != 0 ? (sticky ? "t" : "x") : (sticky ? "T" : "-"));
        return sb.toString();
    }
}
