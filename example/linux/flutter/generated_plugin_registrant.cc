//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <smart_usb/smart_usb_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
  g_autoptr(FlPluginRegistrar) smart_usb_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "SmartUsbPlugin");
  smart_usb_plugin_register_with_registrar(smart_usb_registrar);
}
