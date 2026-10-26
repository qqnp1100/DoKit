#import "DoraemonBackendConfig.h"
//
//  DoraemonNetworkUtil.m
//  DoraemonKit
//
//  Created by yixiang on 2019/2/28.
//

#import "DoraemonNetworkUtil.h"

@interface DoraemonNetworkUtil()<NSURLSessionDelegate>

@end

@implementation DoraemonNetworkUtil

+ (nonnull DoraemonNetworkUtil *)shareInstance{
    static dispatch_once_t once;
    static DoraemonNetworkUtil *instance;
    dispatch_once(&once, ^{
        instance = [[DoraemonNetworkUtil alloc] init];
    });
    return instance;
}

+ (void)requestURL:(NSString *)url method:(NSString *)method param:(NSDictionary *)param success:(DoraemonNetworkSucceedCallback)successAction
             error:(DoraemonNetworkFailureCallback)errorAction{
    NSError *error;
    if (!param) {
        param = @{};
    }
    NSData *postData = [NSJSONSerialization dataWithJSONObject:param options:0 error:&error];
    if (!error) {
        NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:url]];
        request.HTTPMethod = method;
        if (DoraemonBackendOwnURL(request.URL) && DoraemonBackendToken().length) [request setValue:DoraemonBackendToken() forHTTPHeaderField:@"X-DoKit-Token"];
        [request addValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
        [request addValue:@"application/json" forHTTPHeaderField:@"Accept"];
        [request setHTTPBody:postData];
        
        NSURLSession *session = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.defaultSessionConfiguration delegate:[self shareInstance] delegateQueue:nil];
        NSURLSessionDataTask *task = [session dataTaskWithRequest:request completionHandler:^(NSData * _Nullable data, NSURLResponse * _Nullable response, NSError * _Nullable error) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (error) {
                    errorAction(error);
                }else{
                    NSError *error2;
                    id result = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error2];
                    if (!error2) {
                        successAction(result);
                    }
                }
            });
        }];
        [task resume];
    }
}

// get 请求
+ (void)getWithUrlString:(NSString *)url params:(NSDictionary *)params success:(DoraemonNetworkSucceedCallback)successAction
                   error:(DoraemonNetworkFailureCallback)errorAction{
    NSMutableString *mutableUrl = [[NSMutableString alloc] initWithString:url];
    if ([params allKeys].count>0) {
        [mutableUrl appendString:@"?"];
        for (id key in params) {
            NSString *value = [[[params objectForKey:key] description] stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet URLQueryAllowedCharacterSet]];
            [mutableUrl appendString:[NSString stringWithFormat:@"%@=%@&", key, value]];
        }
        mutableUrl = [[mutableUrl substringToIndex:mutableUrl.length - 1] mutableCopy];
    }
    NSString *urlEnCode = [mutableUrl stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet URLQueryAllowedCharacterSet]];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:urlEnCode]];
    if (DoraemonBackendOwnURL(request.URL) && DoraemonBackendToken().length) [request setValue:DoraemonBackendToken() forHTTPHeaderField:@"X-DoKit-Token"];
    NSOperationQueue *queue = [[NSOperationQueue alloc] init];
    NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
    NSURLSession *session = [NSURLSession sessionWithConfiguration:config delegate:[self shareInstance] delegateQueue:queue];
    NSURLSessionDataTask *dataTask = [session dataTaskWithRequest:request completionHandler:^(NSData * _Nullable data, NSURLResponse * _Nullable response, NSError * _Nullable error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (error) {
                errorAction(error);
            } else {
                NSDictionary *dic = [NSJSONSerialization JSONObjectWithData:data options:NSJSONReadingMutableContainers error:nil];
                successAction(dic);
            }
        });
    }];
    [dataTask resume];
}

// post 请求
+ (void)postWithUrlString:(NSString *)url params:(NSDictionary *)params success:(DoraemonNetworkSucceedCallback)successAction
                    error:(DoraemonNetworkFailureCallback)errorAction{
    [DoraemonNetworkUtil requestURL:url method:@"POST" param:params success:successAction error:errorAction];
}

// patch请求
+ (void)patchWithUrlString:(NSString *)url params:(NSDictionary *)params success:(DoraemonNetworkSucceedCallback)successAction error:(DoraemonNetworkFailureCallback)errorAction{
    [DoraemonNetworkUtil requestURL:url method:@"PATCH" param:params success:successAction error:errorAction];
}



#pragma mark - NSURLSessionDelegate 代理方法

//主要就是处理HTTPS请求的
- (void)URLSession:(NSURLSession *)session didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential *))completionHandler
{
    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
}


- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request completionHandler:(void (^)(NSURLRequest *))completionHandler {
    NSMutableURLRequest *safeRequest = request.mutableCopy;
    if (!DoraemonBackendOwnURL(request.URL)) [safeRequest setValue:nil forHTTPHeaderField:@"X-DoKit-Token"];
    completionHandler(safeRequest);
}
@end
