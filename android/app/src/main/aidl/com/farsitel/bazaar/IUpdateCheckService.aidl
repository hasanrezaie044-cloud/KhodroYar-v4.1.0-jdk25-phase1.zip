// Bazaar IPC contract, first two methods in their original transaction order.
// Reference: cafebazaar/BazaarUpdater @ 670bdfd0cf7bd57c03ec503a78e1bcddd8fe29b6
// Apache-2.0; full terms in res/raw/bazaar_updater_license.txt.
package com.farsitel.bazaar;
interface IUpdateCheckService {
    long getVersionCode(String packageName);
    long getRemoteVersionCode(String packageName);
}
