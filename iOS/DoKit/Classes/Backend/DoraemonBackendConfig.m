#import "DoraemonBackendConfig.h"

static NSDictionary *configuration;
static BOOL locked;
static NSDictionary *defaults(void) { return @{@"platformBaseUrl": @"https://dokit.example.internal", @"mockBaseUrl": @"https://dokit.example.internal", @"resourceBaseUrl": @"https://dokit.example.internal/resources", @"webSocketUrl": @"wss://dokit.example.internal/ws", @"projectId": @"", @"sdkToken": @"", @"queryTimeoutMs": @(8000), @"hybridJsUrl": @"https://dokit.example.internal/resources/dokit.js", @"vConsoleUrl": @"https://dokit.example.internal/resources/vconsole.min.js"}; }

id DoraemonBackendValue(NSString *key) {
    @synchronized(NSProcessInfo.class) { return (configuration ?: defaults())[key]; }
}
void DoraemonBackendLock(void) { @synchronized(NSProcessInfo.class) { locked = YES; } }
void DoraemonBackendConfigure(NSDictionary<NSString *, id> *options) {
    @synchronized(NSProcessInfo.class) {
        if (locked) [NSException raise:NSInternalInconsistencyException format:@"Configure DoKit before installation; restart to change backend"];
        NSMutableDictionary *next = defaults().mutableCopy;
        [next addEntriesFromDictionary:options];
        for (NSString *key in @[@"platformBaseUrl", @"mockBaseUrl", @"resourceBaseUrl", @"webSocketUrl", @"hybridJsUrl", @"vConsoleUrl"]) {
            id value = next[key];
            BOOL ws = [key isEqualToString:@"webSocketUrl"];
            NSURLComponents *url = [value isKindOfClass:NSString.class] ? [NSURLComponents componentsWithString:value] : nil;
            NSArray *schemes = ws ? @[@"ws", @"wss"] : @[@"http", @"https"];
            if (!url.host.length || ![schemes containsObject:url.scheme.lowercaseString] || url.user || url.password || url.fragment || (!ws && url.query) ||
                [value rangeOfCharacterFromSet:[NSCharacterSet characterSetWithCharactersInString:@"\r\n\t \\\"'<>"]].location != NSNotFound ||
                (url.port && (url.port.integerValue < 1 || url.port.integerValue > 65535)))
                [NSException raise:NSInvalidArgumentException format:@"Invalid DoKit %@", key];
            while ([value hasSuffix:@"/"]) value = [value substringToIndex:[value length] - 1];
            next[key] = value;
        }
        id token = next[@"sdkToken"], project = next[@"projectId"], timeout = next[@"queryTimeoutMs"];
        if (![token isKindOfClass:NSString.class] || [token rangeOfCharacterFromSet:[[NSCharacterSet characterSetWithCharactersInString:@"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~+/=:-"] invertedSet]].location != NSNotFound || ![project isKindOfClass:NSString.class] || ![timeout isKindOfClass:NSNumber.class] || [timeout doubleValue] < 100 || [timeout doubleValue] > 60000)
            [NSException raise:NSInvalidArgumentException format:@"Invalid DoKit token, projectId or queryTimeoutMs"];
        configuration = next.copy;
    }
}
NSString *DoraemonBackendPlatformURL(NSString *path) { return [DoraemonBackendValue(@"platformBaseUrl") stringByAppendingString:path]; }
NSString *DoraemonBackendMockDomain(void) { return [DoraemonBackendValue(@"mockBaseUrl") stringByAppendingString:@"/"]; }
NSString *DoraemonBackendToken(void) { return DoraemonBackendValue(@"sdkToken"); }
BOOL DoraemonBackendOwnURL(NSURL *url) {
    for (NSString *key in @[@"platformBaseUrl", @"mockBaseUrl", @"resourceBaseUrl"]) {
        NSURL *own = [NSURL URLWithString:DoraemonBackendValue(key)];
        NSNumber *port = url.port ?: @([url.scheme.lowercaseString isEqualToString:@"https"] ? 443 : 80);
        NSNumber *ownPort = own.port ?: @([own.scheme.lowercaseString isEqualToString:@"https"] ? 443 : 80);
        NSString *prefix = own.path ?: @"";
        if ([own.scheme.lowercaseString isEqualToString:url.scheme.lowercaseString] &&
            [own.host.lowercaseString isEqualToString:url.host.lowercaseString] && [port isEqualToNumber:ownPort] &&
            (!prefix.length || [prefix isEqualToString:@"/"] || [url.path isEqualToString:prefix] || [url.path hasPrefix:[prefix stringByAppendingString:@"/"]])) return YES;
    }
    return NO;
}
