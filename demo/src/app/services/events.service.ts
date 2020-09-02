import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import { Options } from '../model/options';


@Injectable({
  providedIn: 'root'
})
export class EventsService {

  private uploadOptionsChange = new Subject<boolean>();

  publishUploadOptionsChange(change: boolean) {
    this.uploadOptionsChange.next(change);
  }

  getUploadOptionsChange(): Subject<boolean> {
    return this.uploadOptionsChange;
  }
}
