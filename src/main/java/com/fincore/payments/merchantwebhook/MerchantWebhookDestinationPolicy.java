package com.fincore.payments.merchantwebhook;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Deliberately local sandbox destinations; tenant input never causes DNS resolution. */
public final class MerchantWebhookDestinationPolicy {
    private MerchantWebhookDestinationPolicy() { }

    public static String canonical(String raw) {
        try {
            if (raw==null || raw.length()>512 || !raw.matches("[!-~]+")) throw new IllegalArgumentException();
            URI uri=URI.create(raw);
            String host=uri.getHost(),path=uri.getRawPath();
            if (!Set.of("http","https").contains(uri.getScheme())
                    || host==null || !Set.of("127.0.0.1","[::1]","::1").contains(host)
                    || uri.getPort()<1 || uri.getPort()>65535
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || path==null || !path.matches("/[A-Za-z0-9/_-]*") || path.contains("//"))
                throw new IllegalArgumentException();
            String literal=host.equals("127.0.0.1") ? host : "[::1]";
            String canonical=uri.getScheme()+"://"+literal+":"+uri.getPort()+path;
            if (!raw.equals(canonical) || !uri.normalize().toString().equals(raw)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("Merchant webhook destination is not a canonical approved sandbox URL.");
        }
    }

    public static boolean allowed(String url,List<String> allowlist) {
        try { return allowlist!=null && allowlist.contains(canonical(url)); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
