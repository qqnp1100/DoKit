/// Backend configuration for Flutter's existing startup telemetry.
/// Flutter does not implement the native Mock or WebSocket protocols.
class DoKitBackendConfig {
  final String platformBaseUrl;
  final String projectId;
  final String sdkToken;
  const DoKitBackendConfig({
    this.platformBaseUrl = const String.fromEnvironment('DOKIT_PLATFORM_URL', defaultValue: 'https://dokit.example.internal'),
    this.projectId = const String.fromEnvironment('DOKIT_PROJECT_ID'),
    this.sdkToken = const String.fromEnvironment('DOKIT_SDK_TOKEN', defaultValue: ''),
  });

  Uri get startupUri {
    final base = Uri.parse(platformBaseUrl);
    if (!['http', 'https'].contains(base.scheme) || base.host.isEmpty || base.userInfo.isNotEmpty ||
        base.hasQuery || base.hasFragment || RegExp(r'''[\s\\<>"']''').hasMatch(platformBaseUrl)) {
      throw ArgumentError('Invalid DoKit platformBaseUrl');
    }
    if (!RegExp(r'^[A-Za-z0-9._~+/=:-]*$').hasMatch(sdkToken)) throw ArgumentError('Invalid DoKit sdkToken');
    return Uri.parse(platformBaseUrl.replaceFirst(RegExp(r'/+$'), '') + '/uploadAppData');
  }
}
