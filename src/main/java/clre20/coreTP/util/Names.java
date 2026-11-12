package clre20.coreTP.util;

import java.util.regex.Pattern;

/** 家與地標名稱的驗證。 */
public final class Names {

    private static final Pattern VALID = Pattern.compile("^[\\p{L}\\p{N}_-]+$");

    private Names() {
    }

    public static boolean valid(String name, int maxLength) {
        return name != null && !name.isEmpty() && name.length() <= maxLength && VALID.matcher(name).matches();
    }
}
