package io.siggi.databackup.osutils;

import java.util.UUID;

public record ApfsSnapshot(String device, UUID uuid, String name, long xid) {
}
