#import <Cordova/CDV.h>
#import "FileTransferBackground.h"
#import <AFNetworking/AFNetworking.h>
#import "FileUploader.h"

@interface FileTransferBackground()
@property (nonatomic, strong) CDVInvokedUrlCommand* pluginCommand;
@end
@implementation FileTransferBackground
-(void)initManager:(CDVInvokedUrlCommand*)command{
    [self runBlockInBackgroundWithTryCatch:^{
        self.pluginCommand = command;
        if (command.arguments.count > 0){
            NSDictionary* config = command.arguments[0];
            FileUploader.parallelUploadsLimit = ((NSNumber*)config[@"parallelUploadsLimit"]).integerValue;
        }

        [FileUploader sharedInstance].delegate = self;

        [self sendCallback:@{
            @"state" : @"INITIALIZED"
        }];

        //mark all old uploads as failed to be retried
        for (NSString* uploadId in [self getV1Uploads]){
            [self sendCallback:@{
                @"state" : @"FAILED",
                @"id" : uploadId,
                @"error": @"upload failed",
                @"errorCode" : @500
            }];
        }


        for (UploadEvent* event in [UploadEvent allEvents]){
            [self uploadManagerDidReceiveCallback: [event dataRepresentation]];
        }
    } forCommand:command];
}

-(void)startUpload:(CDVInvokedUrlCommand*)command{
    [self runBlockInBackgroundWithTryCatch:^{
        NSDictionary* payload = command.arguments[0];
        __weak FileTransferBackground *weakSelf = self;
        [[FileUploader sharedInstance] addUpload:payload
                               completionHandler:^(NSError* error) {
            if (error){
                [weakSelf sendCallback:@{
                    @"error" : error.localizedDescription,
                    @"id" : payload[@"id"],
                    @"errorCode" : @(error.code)
                }];
            }
        }];
    } forCommand:command];
}

-(void)removeUpload:(CDVInvokedUrlCommand*)command{
    [self runBlockInBackgroundWithTryCatch:^{
        [[FileUploader sharedInstance] removeUpload:command.arguments[0]];
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [pluginResult setKeepCallback:@YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    } forCommand:command];
}

-(void)uploadManagerDidReceiveCallback:(NSDictionary*)info{
    [self sendCallback:info];
}

-(void)sendCallback:(NSDictionary*)data{
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:data];
    [pluginResult setKeepCallback:@YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.pluginCommand.callbackId];
}

-(void)runBlockInBackgroundWithTryCatch:(void (^)(void))block forCommand:(CDVInvokedUrlCommand*)command{
    [self.commandDelegate runInBackground:^{
        @try {
            block();
        } @catch (NSException *exception) {
            [self sendErrorCallback:command forException:exception];
        }
    }];
}

-(void)acknowledgeEvent:(CDVInvokedUrlCommand*)command{
    [self runBlockInBackgroundWithTryCatch:^{
        [[FileUploader sharedInstance] acknowledgeEventReceived:command.arguments[0]];
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [pluginResult setKeepCallback:@YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    } forCommand:command];
}

-(void)destroy:(CDVInvokedUrlCommand*)command{
    self.pluginCommand = nil;
}

-(NSArray*)getV1Uploads{
    //returns uploads made by older version of the plugin
    NSMutableArray* oldUploadIds = [[NSMutableArray alloc] init];
    NSURL* cachess = [[NSFileManager defaultManager] URLForDirectory:NSCachesDirectory inDomain:NSUserDomainMask appropriateForURL:nil create:YES error:NULL];
    NSURL* workDirectoryURL = [cachess URLByAppendingPathComponent:@"FileUploadManager"];
    NSArray* directoryContents = [[NSFileManager defaultManager] contentsOfDirectoryAtURL:workDirectoryURL
                                                               includingPropertiesForKeys:@[NSURLIsDirectoryKey]
                                                                                  options:NSDirectoryEnumerationSkipsSubdirectoryDescendants
                                                                                    error:nil];
    for (NSURL * itemURL in directoryContents) {
        NSString* directoryName = [itemURL lastPathComponent];
        if ([directoryName hasPrefix:@"Upload-"]) {
            NSString* name = [[directoryName componentsSeparatedByString:@"*"] firstObject];
            NSString* uploadId = [name stringByReplacingOccurrencesOfString:@"Upload-" withString:@""];
            [oldUploadIds addObject:uploadId];
        }
    }
    //remove the old uploads directory
    [[NSFileManager defaultManager] removeItemAtURL:workDirectoryURL error:nil];
    return oldUploadIds;
}

-(void)sendErrorCallback:(CDVInvokedUrlCommand*)command forException:(NSException*)exception{
    NSString* message = [NSString stringWithFormat:@"(%@) - %@", exception.name, exception.reason];
    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsString:message] callbackId:command.callbackId];
}
@end
