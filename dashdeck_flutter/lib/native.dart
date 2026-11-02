import 'dart:convert';

import 'package:flutter/services.dart';

import 'models.dart';

/// Thin wrapper over the `dashdeck/app` channel implemented in `bridge/MainActivity.kt`.
class Native {
  static const _channel = MethodChannel('dashdeck/app');

  static Future<DashConfig> getConfig() async => _decode(await _channel.invokeMethod<String>('getConfig'));

  static Future<void> setConfig(DashConfig config) => _channel.invokeMethod('setConfig', jsonEncode(config.toJson()));

  static Future<DashConfig> resetConfig() async => _decode(await _channel.invokeMethod<String>('resetConfig'));

  static Future<void> resetTrip() => _channel.invokeMethod('resetTrip');

  /// True once location and every car-data permission Android Auto offers are granted.
  static Future<bool> hasPermissions() async => await _channel.invokeMethod<bool>('hasPermissions') ?? false;

  /// Shows the system prompts; resolves to whether everything is now granted.
  static Future<bool> requestPermissions() async => await _channel.invokeMethod<bool>('requestPermissions') ?? false;

  /// PNG of [page] drawn by the same renderer the car uses, outlining [highlight] if given.
  static Future<Uint8List?> renderPreview({required int page, required int width, required int height, String? highlight}) =>
      _channel.invokeMethod<Uint8List>(
        'renderPreview',
        {'page': page, 'width': width, 'height': height, 'highlight': highlight},
      );

  /// Opens the system photo picker; resolves to the updated config, or null if cancelled.
  static Future<DashConfig?> pickWallpaper() async {
    final raw = await _channel.invokeMethod<String>('pickWallpaper');
    return raw == null ? null : _decode(raw);
  }

  /// Whether DashDeck may read what music apps are playing (needs notification access).
  static Future<bool> mediaAccess() async => await _channel.invokeMethod<bool>('mediaAccess') ?? false;

  /// Opens the system's notification-access list, where the user switches DashDeck on.
  static Future<void> openMediaAccess() => _channel.invokeMethod('openMediaAccess');
  static Future<void> openAppInfo() => _channel.invokeMethod('openAppInfo');

  /// `{running: bool, tapControl: bool}` for phone mirroring.
  static Future<Map<String, bool>> mirrorStatus() async {
    final status = await _channel.invokeMapMethod<String, bool>('mirrorStatus');
    return status ?? const {};
  }

  static Future<bool> startMirror() async => await _channel.invokeMethod<bool>('startMirror') ?? false;
  static Future<void> stopMirror() => _channel.invokeMethod('stopMirror');
  static Future<void> openTapSettings() => _channel.invokeMethod('openTapSettings');

  /// Whether Android Auto will accept this install, plus a short technical description.
  static Future<({bool ready, String detail})> androidAutoStatus() async {
    final map = await _channel.invokeMapMethod<String, Object?>('androidAutoStatus') ?? const {};
    return (ready: map['ready'] == true, detail: map['detail'] as String? ?? '');
  }

  static DashConfig _decode(String? raw) => DashConfig.fromJson(jsonDecode(raw!) as Map<String, dynamic>);
}
