import { Component, NgZone, ViewChild, ElementRef, OnInit} from '@angular/core';
import { ImagePicker } from '@ionic-native/image-picker/ngx';
import { WebView } from '@ionic-native/ionic-webview/ngx';
import { NativeStorage } from '@ionic-native/native-storage/ngx';

import { Platform, NavController } from '@ionic/angular';

import { Media } from '../model/media';
import { Options, DEFAULT as DEFAULT_OPTIONS } from '../model/options';
import { EventsService } from '../services/events.service';

declare var FileTransferManager: any;

@Component({
  selector: 'app-home',
  templateUrl: 'home.page.html',
  styleUrls: ['home.page.scss'],
})

export class HomePage implements OnInit {

  allMedia: Array<Media> = [];
  pendingMedia: Array<Media> = [];
  logs: Array<String> = [];
  uploader: any;
  uploadOptions: Options = DEFAULT_OPTIONS; 

  @ViewChild('logs_container', {read: ElementRef, static: true }) logsContainer: ElementRef;

  constructor(
    private platform: Platform, 
    private _ngZone: NgZone, 
    private events: EventsService,
    private navController: NavController, 
    private imgPicker: ImagePicker, 
    private webView: WebView, 
    private nativeStorage: NativeStorage) {

    this.platform.ready().then(() => {

      this.uploader = FileTransferManager.init({
        parallelUploadsLimit: 2,
        notificationTitle: 'Upload service',
        notificationContent: 'Background upload service running'
      }, event => {
        const correspondingMedia = this.getMediaWithId(event.id);
        if (!correspondingMedia) return;

        if (event.state == 'UPLOADED') {
          this.log("Upload: " + event.id + " has been completed successfully");
          console.log(event.statusCode, event.serverResponse);
          correspondingMedia.updateStatus("Uploaded successfully");
        } else if (event.state == 'FAILED') {
          if (event.id) {
            this.log("Upload: " + event.id + " has failed");
            correspondingMedia.updateStatus("Error while uploading");
          } else {
            console.error("uploader caught an error: " + event.error);
          }
        } else if (event.state == 'UPLOADING') {
          this.log("Uploading: " + event.id + " progress: " + event.progress + "%");
          correspondingMedia.updateStatus("Uploading: " + event.progress + "%");
        }
          
        if (event.eventId)
          this.uploader.acknowledgeEvent(event.eventId);
      });
    })

    this.events.getUploadOptionsChange().subscribe(() =>  { this.loadUploadOptions() });
  }

  ngOnInit() {
    this.loadUploadOptions();
  }

  cancelUpload(media: Media): void {
    this.uploader.removeUpload(media.id, res => {
      media.updateStatus("Aborted");
      this.log("Upload: " + media.id + " aborted");
    }, err => alert('Error removing upload'));
  }

  openGallery(): void {
    this.imgPicker.getPictures({
      maximumImagesCount: 3
    }).then(file_uris => {
      if(typeof file_uris == 'string') return;
      file_uris.forEach(file_uri => {
        const media = new Media(file_uri, this.webView.convertFileSrc(file_uri), this._ngZone);
        this.allMedia.push(media);
        this.log("Upload: " + media.id + " added");
      });
      this.refreshRemainsMediaToUpload();
    }, err => console.log('err: ' + err));
  }

  startUpload(media: Media) {
    const options: any = {
      serverUrl: this.uploadOptions.serverUrl,
      filePath: media.uri,
      fileKey: this.uploadOptions.fileKey,
      id: media.id,
      notificationTitle: "Uploading image",
      headers: this.getHeadersHash(this.uploadOptions.headers),
      parameters: this.uploadOptions.parameters,
      requestMethod: this.uploadOptions.requestMethod
    };
    this.uploader.startUpload(options);
    media.updateStatus("Uploading...");
    this.log("Upload: " + media.id + " start");
  }

  removeUpload(media: Media) {
    this.allMedia = this.allMedia.filter(m => m.id != media.id);
    this.refreshRemainsMediaToUpload();
  }

  async uploadAll() {
    this.refreshRemainsMediaToUpload();
    while(this.pendingMedia.length > 0) {
      this.startUpload(this.pendingMedia.pop());
      await this.sleep(400);
    }
  }

  openSettings() {
    this.navController.navigateForward('/settings');
  }

  private async loadUploadOptions() {
    try {
      const uploadOptions = await this.nativeStorage.getItem('upload_options');
      if(uploadOptions) this.uploadOptions = uploadOptions;
    }catch(error) {}
  }

  private getHeadersHash(headers: Options["headers"]) {
    const headersHash = {};
    headers.forEach(header => headersHash[header.key] = header.value)
    return headersHash;
  }

  private getMediaWithId(mediaId) {
    return this.allMedia.find(media => media.id == mediaId);
  }

  private refreshRemainsMediaToUpload() {
    this.pendingMedia = this.allMedia.filter(media => !media.status);
  }

  private log(message: String) {
    this._ngZone.run(() => {
      this.logs.push(message);
      setTimeout(() => {
        this.logsContainer.nativeElement.scrollTop = this.logsContainer.nativeElement.scrollHeight;
      },200)
    });
  }

  private async sleep(time) {
    return new Promise((resolve,reject) => setTimeout(resolve, time))
  }
}
