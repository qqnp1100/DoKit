#import <Foundation/Foundation.h>

/// Call once before installing DoKit. Credentials stay in memory.
FOUNDATION_EXPORT void DoraemonBackendConfigure(NSDictionary<NSString *, id> *options);
FOUNDATION_EXPORT void DoraemonBackendLock(void);
FOUNDATION_EXPORT id DoraemonBackendValue(NSString *key);
FOUNDATION_EXPORT NSString *DoraemonBackendPlatformURL(NSString *path);
FOUNDATION_EXPORT NSString *DoraemonBackendMockDomain(void);
FOUNDATION_EXPORT NSString *DoraemonBackendToken(void);
FOUNDATION_EXPORT BOOL DoraemonBackendOwnURL(NSURL *url);
