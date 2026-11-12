package clre20.coreTP.storage;

import clre20.coreTP.util.Pos;

public record BackEntry(Pos pos, long timestamp, boolean death) {
}
