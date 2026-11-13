package local.jile.v5;

import java.net.URI;

final class OtaChannel {
    private OtaChannel() {}

    static URI feed(String value) {
        URI uri = URI.create(value);
        if (value.length() > 500 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.normalize().equals(uri) || uri.getRawPath() == null
                || !uri.getRawPath().matches("/[A-Za-z0-9/._-]+\\.json")) {
            throw new IllegalArgumentException("请输入 HTTPS 更新清单地址（.json）");
        }
        return uri;
    }

    static URI apk(URI feed, String filename) {
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.apk")) {
            throw new IllegalArgumentException("更新清单中的 APK 文件名无效");
        }
        return feed.resolve(filename);
    }
}
