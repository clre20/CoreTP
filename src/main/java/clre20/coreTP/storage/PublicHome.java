package clre20.coreTP.storage;

import clre20.coreTP.util.Pos;

import java.util.UUID;

/** 公開的家的索引項目（擁有者離線時也能列出與傳送）。 */
public record PublicHome(UUID owner, String ownerName, String name, Pos pos) {
}
