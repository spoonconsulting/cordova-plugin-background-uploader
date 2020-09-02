import { Component, OnInit } from '@angular/core';
import { NavController } from '@ionic/angular';
import { NativeStorage } from '@ionic-native/native-storage/ngx';
import { Options, DEFAULT as DEFAULT_OPTIONS } from '../model/options';
import { EventsService } from '../services/events.service';

@Component({
  selector: 'app-settings',
  templateUrl: './settings.page.html',
  styleUrls: ['./settings.page.scss'],
})
export class SettingsPage implements OnInit {

  options : Options = DEFAULT_OPTIONS;

  constructor(private events:EventsService, private nativeStorage: NativeStorage, private navController: NavController) { }

  async ngOnInit() {
    try {
      const options = await this.nativeStorage.getItem('upload_options');
      if(options) this.options = options;
    }catch(error) {}
  }

  addHeader() {
    const headers = this.options.headers;
    const lastHeaderId = headers.length > 0 ? headers[headers.length -1].id : - 1;

    this.options.headers.push({
      id: lastHeaderId + 1,
      key: '',
      value: ''
    });
  }

  removeHeader(id) {
    this.options.headers = this.options.headers.filter(header => header.id != id);
  }

  save() {
    this.nativeStorage.setItem('upload_options', this.options);
    this.events.publishUploadOptionsChange(true);
    this.navController.back();
  }

}
