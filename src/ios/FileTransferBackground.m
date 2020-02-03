/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

#include <sys/types.h>
#include <sys/sysctl.h>
#include "TargetConditionals.h"
#import <Cordova/CDV.h>
#import "FileTransferBackground.h"


@implementation FileTransferBackground


NSString *const FormatTypeName[5] = {
    [kFileUploadStateStopped] = @"STOPPED",
    [kFileUploadStateStarted] = @"UPLOADING",
    [kFileUploadStateUploaded] = @"UPLOADED",
    [kFileUploadStateFailed] = @"FAILED",
    [kFileUploadStateStopping] = @"STOPPING",
};


-(void)initManager:(CDVInvokedUrlCommand*)command{
    lastProgressTimeStamp = 0;
    pluginCommand = command;
    
    [FileUploadManager sharedInstance].delegate = self;
    [[FileUploadManager sharedInstance] start];
    
    NSArray* uploads= [[FileUploadManager sharedInstance].uploads allObjects];
    for (FileUpload *upload in uploads) {
        CDVPluginResult* pluginResult;
        if(upload.state == kFileUploadStateFailed) {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                         messageAsDictionary:@{@"error":[@"upload failed: " stringByAppendingString:upload.error.description],
                                                               @"id" :[[FileUploadManager sharedInstance] getFileIdForUpload:upload],
                                                               @"state": FormatTypeName[upload.state]
                                                               }];
            [pluginResult setKeepCallback:@YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            //delete upload info from disk
            [upload remove];
            
        }else if(upload.state == kFileUploadStateUploaded) {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                         messageAsDictionary:@{@"completed":@YES,
                                                               @"id" :[[FileUploadManager sharedInstance] getFileIdForUpload:upload],
                                                               @"state": FormatTypeName[upload.state],
                                                               @"serverResponse": upload.serverResponse,
                                                               @"statusCode": @(upload.response.statusCode)
                                                               }];
            [pluginResult setKeepCallback:@YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            //delete upload info from disk
            [upload remove];
            
            
        }
        
        
    }
    
}

- (void)startUpload:(CDVInvokedUrlCommand*)command
{
    [self.commandDelegate runInBackground:^{
        NSDictionary* payload = command.arguments[0];
        NSString* uploadUrl  = payload[@"serverUrl"];
        NSString* filePath  = payload[@"filePath"];
        NSDictionary*  headers = payload[@"headers"];
        NSDictionary* parameters = payload[@"parameters"];
        NSString* fileId = payload[@"id"];
        
        if (uploadUrl == nil) {
            return [self returnError:command withInfo:@{@"id":fileId, @"message": @"invalid url"}];
        }
        
        if (filePath == nil) {
            return [self returnError:command withInfo:@{@"id":fileId, @"message": @"file path is required"}];
        }
        
        
        if (![[NSFileManager defaultManager] fileExistsAtPath:filePath] ) {
            return [self returnError:command withInfo:@{@"id":fileId, @"message": @"file does not exists"}];
        }
        
        if (parameters == nil) {
            parameters = @{};
        }
        
        if (headers == nil) {
            headers = @{};
        }
        
        FileUploadManager* uploader = [FileUploadManager sharedInstance];
        FileUpload* upload = [uploader getUploadById:fileId];
        if (upload){
            NSLog(@"Request to upload %@ has been ignored since it is already being uploaded or is present in upload list" ,fileId);
            return;
        }
        
        
        NSURL * url = [NSURL URLWithString:uploadUrl];
        
        NSMutableURLRequest * request = [NSMutableURLRequest requestWithURL:url];
        [request setHTTPMethod:@"POST"];
        
        
        NSString *boundary = [NSString stringWithFormat:@"Boundary-%@", [[NSUUID UUID] UUIDString]];
        
        NSString *contentType = [NSString stringWithFormat:@"multipart/form-data; boundary=%@", boundary];
        [request setValue:contentType forHTTPHeaderField: @"Content-Type"];
        
        
        NSData *body = [self createBodyWithBoundary:boundary parameters:parameters paths:@[filePath] fieldName:payload[@"fileKey"]];
        
        for (NSString *key in headers) {
            [request setValue:[headers objectForKey:key] forHTTPHeaderField:key];
        }
        
        
        NSString *tmpFilePath = [NSTemporaryDirectory() stringByAppendingPathComponent:boundary];
        if (![body writeToFile:tmpFilePath atomically:YES] ) {
            
            CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                        messageAsDictionary:@{
                                                                                @"error" : @"Error writing temp file",
                                                                                @"id" : fileId
                                                                                }];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            return;
        }
        
        
        FileUpload* job=[uploader createUploadWithRequest:request fileId:fileId fileURL:[NSURL URLWithString:[NSString stringWithFormat:@"file:%@", tmpFilePath]]];
        
        if(job){
            [job start];
        }else{
            CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                        messageAsDictionary:@{
                                                                                @"error" : @"Error adding upload",
                                                                                @"id" : fileId
                                                                                }];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        }
        
    }];
}

- (void)removeUpload:(CDVInvokedUrlCommand*)command
{
    NSString* fileId = command.arguments[0];
    FileUploadManager* uploader = [FileUploadManager sharedInstance];
    
    FileUpload* upload =[uploader getUploadById:fileId];
    if (upload){
        if (upload.state == kFileUploadStateStarted)
            [upload stop];
        [upload remove];
    }
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [pluginResult setKeepCallback:@YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    
}

- (NSData *)createBodyWithBoundary:(NSString *)boundary
                        parameters:(NSDictionary *)parameters
                             paths:(NSArray *)paths
                         fieldName:(NSString *)fieldName {
    NSMutableData *httpBody = [NSMutableData data];
    
    [parameters enumerateKeysAndObjectsUsingBlock:^(NSString *parameterKey, NSString *parameterValue, BOOL *stop) {
        [httpBody appendData:[[NSString stringWithFormat:@"--%@\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"\r\n\r\n", parameterKey] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:[[NSString stringWithFormat:@"%@\r\n", parameterValue] dataUsingEncoding:NSUTF8StringEncoding]];
    }];
    
    
    for (NSString *path in paths) {
        NSString *filename  = [path lastPathComponent];
        NSData   *data      = [NSData dataWithContentsOfFile:path];
        NSString *mimetype  = @"application/octet-stream";
        
        [httpBody appendData:[[NSString stringWithFormat:@"--%@\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"; filename=\"%@\"\r\n", fieldName, filename] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:[[NSString stringWithFormat:@"Content-Type: %@\r\n\r\n", mimetype] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:data];
        [httpBody appendData:[@"\r\n" dataUsingEncoding:NSUTF8StringEncoding]];
    }
    
    [httpBody appendData:[[NSString stringWithFormat:@"--%@--\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
    
    return httpBody;
}


- (void)uploadManager:(FileUploadManager *)manager willCreateSessionWithConfiguration:(NSURLSessionConfiguration *)configuration
{
    
    configuration.HTTPMaximumConnectionsPerHost =1;
    configuration.requestCachePolicy = NSURLRequestReloadIgnoringCacheData;
    configuration.sessionSendsLaunchEvents = NO;
}

- (void)uploadManager:(FileUploadManager *)manager didChangeStateForUpload:(FileUpload *)upload{
    
    if (upload.state == kFileUploadStateUploaded) {
        //upload for a file completed
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                      messageAsDictionary:@{
                                                                            @"completed":@YES,
                                                                            @"id" :[[FileUploadManager sharedInstance] getFileIdForUpload:upload],
                                                                            @"state": FormatTypeName[upload.state],
                                                                            @"serverResponse": upload.serverResponse,
                                                                            @"statusCode": @(upload.response.statusCode)
                                                                            }];
        [pluginResult setKeepCallback:@YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:pluginCommand.callbackId];
        //delete upload info from disk
        [upload remove];
        
    }
    else if (upload.state == kFileUploadStateFailed) {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:@{
                                                                            @"id" :[[FileUploadManager sharedInstance] getFileIdForUpload:upload],
                                                                            @"error" : [@"upload failed: " stringByAppendingString:upload.error.description],
                                                                            @"state": FormatTypeName[upload.state]
                                                                            }];
        [pluginResult setKeepCallback:@YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:pluginCommand.callbackId];
    }
    else if (upload.state == kFileUploadStateStopped) {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:@{
                                                                            @"id" :[[FileUploadManager sharedInstance] getFileIdForUpload:upload],
                                                                            @"error" : @"upload stopped by user",
                                                                            @"state": FormatTypeName[upload.state]
                                                                            }];
        [pluginResult setKeepCallback:@YES];                                                                           
        [self.commandDelegate sendPluginResult:pluginResult callbackId:pluginCommand.callbackId];
    }
    else  if (upload.state == kFileUploadStateStarted) {
        
        if (upload.progress == 0) {
            return;
        }
        
        float roundedProgress =roundf(10 * (upload.progress*100)) / 10.0;
        NSDictionary* res =@{
            @"progress" : @(roundedProgress),
            @"id" :[[FileUploadManager sharedInstance] getFileIdForUpload:upload],
            @"state": FormatTypeName[upload.state]
        };
        NSTimeInterval currentTimestamp = [[NSDate date] timeIntervalSince1970];
        if(currentTimestamp - lastProgressTimeStamp >= 1){
            lastProgressTimeStamp = currentTimestamp;
            [self sendProgressCallback:res];
        }
    }
    
}
    
-(void)sendProgressCallback:(NSDictionary*)res{
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:res];
    [pluginResult setKeepCallback:@YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:pluginCommand.callbackId];
}


- (void)uploadManager:(FileUploadManager *)manager logWithFormat:(NSString *)format arguments:(va_list)arguments
{
    // +++ Need a better logging story; perhaps QLog from VoIPDemo.
    NSLog(@"%@", [[NSString alloc] initWithFormat:format arguments:arguments]);
}

-(void)returnError:(CDVInvokedUrlCommand *) command withInfo:(NSDictionary*)data  {
    
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus: CDVCommandStatus_ERROR messageAsDictionary:data];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

@end
