package clre20.coreTP;

/** 可獨立開關、各自有一個設定檔的傳送功能。 */
public enum Module {
    TPA("tpa"), HOME("home"), WARP("warp"), BACK("back"), RTP("rtp");

    private final String id;

    Module(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
