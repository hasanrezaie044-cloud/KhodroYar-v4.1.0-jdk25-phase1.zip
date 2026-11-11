// Public IPC interface from Bazaar's update-check documentation:
// https://developers.cafebazaar.ir/fa/guidelines/feature/update-check
// https://s.cafebazaar.ir/2/resources/aidl/IUpdateCheckService.aidl
package com.farsitel.bazaar;
interface IUpdateCheckService {
    long getVersionCode(String packageName);
}
