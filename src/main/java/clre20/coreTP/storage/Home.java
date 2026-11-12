package clre20.coreTP.storage;

import clre20.coreTP.util.Pos;

public record Home(String name, Pos pos, boolean isPublic, long created) {
}
