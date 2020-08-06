/* global FileTransferManager, TestUtils */

exports.defineAutoTests = function () {
  describe('Uploader', function () {
    var originalTimeout
    var sampleFile = 'tree.jpg'
    var path = ''
    var serverHost = window.cordova.platformId === 'android' ? '10.0.2.2' : 'localhost'
    var serverUrl = 'http://' + serverHost + ':3000/upload'
    var nativeUploader

    beforeEach(function (done) {
      originalTimeout = jasmine.DEFAULT_TIMEOUT_INTERVAL
      jasmine.DEFAULT_TIMEOUT_INTERVAL = 60000
      TestUtils.copyFileToDataDirectory(sampleFile).then(function (newPath) {
        path = newPath
        done()
      })
    })

    afterEach(function (done) {
      jasmine.DEFAULT_TIMEOUT_INTERVAL = originalTimeout
      if (nativeUploader) {
        nativeUploader.destroy()
      }
      TestUtils.deleteFile(sampleFile).then(done)
    })

    describe('Add upload', function () {
      it('exposes FileTransferManager globally', function () {
        expect(FileTransferManager).toBeDefined()
      })

      it('should have init function', function () {
        expect(FileTransferManager.init).toBeDefined()
      })

      it('should have startUpload function', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          expect(nativeUploader.startUpload).toBeDefined()
          done()
        })
      })

      it('returns an error if no argument is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.startUpload(null, null, function (result) {
            expect(result.error).toBe('Upload Settings object is missing or has invalid arguments')
            done()
          })
        })
      })

      it('returns an error if upload id is missing', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.startUpload({ }, null, function (result) {
            expect(result.error).toBe('Upload ID is required')
            done()
          })
        })
      })

      it('returns an error if serverUrl is missing', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.startUpload({ id: 'test_id', filePath: path }, null, function (result) {
            expect(result.id).toBe('test_id')
            expect(result.error).toBe('Server URL is required')
            done()
          })
        })
      })

      it('returns an error if serverUrl is invalid', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.startUpload({ id: '123_456', serverUrl: '  ' }, null, function (result) {
            expect(result).toBeDefined()
            expect(result.id).toBe('123_456')
            expect(result.error).toBe('Invalid server URL')
            done()
          })
        })
      })

      it('returns an error if filePath is missing', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.startUpload({ id: 'some_id', serverUrl: serverUrl }, null, function (result) {
            expect(result.id).toBe('some_id')
            expect(result.error).toBe('filePath is required')
            done()
          })
        })
      })

      it('sends upload progress events', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'a_file_id', serverUrl: serverUrl, filePath: path })
          } else if (upload.state === 'UPLOADED') {
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          } else if (upload.state === 'UPLOADING') {
            expect(upload.id).toBe('a_file_id')
            expect(typeof upload.progress).toBe('number')
            expect(upload.eventId).toBeUndefined()
            expect(upload.error).toBeUndefined()
          }
        })
      })

      it('sends success callback when upload is completed', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'abc', serverUrl: serverUrl, filePath: path })
          } else if (upload.state === 'UPLOADED') {
            expect(upload.id).toBe('abc')
            expect(upload.eventId).toBeDefined()
            expect(upload.error).toBeUndefined()
            expect(upload.statusCode).toBe(201)
            var response = JSON.parse(upload.serverResponse)
            delete response.receivedInfo.headers
            expect(response.receivedInfo).toEqual({
              originalFilename: sampleFile,
              accessMode: 'public',
              height: 4048,
              grayscale: false,
              width: 3036,
              parameters: {}
            })
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          }
        })
      })

      it('upload success with put method', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'file_id', serverUrl: serverUrl, filePath: path, requestMethod: 'PUT' })
          } else if (upload.state === 'UPLOADED') {
            expect(upload.id).toBe('file_id')
            expect(upload.statusCode).toBe(200)
            expect(upload.eventId).toBeDefined()
            expect(upload.error).toBeUndefined()
            var response = JSON.parse(upload.serverResponse)
            delete response.receivedInfo.headers
            expect(response.receivedInfo).toEqual({
              originalFilename: sampleFile,
              accessMode: 'public',
              height: 4048,
              grayscale: false,
              width: 3036,
              parameters: {}
            })
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          }
        })
      })

      it('sends headers during upload', function (done) {
        var headers = { signature: 'secret_hash', source: 'test' }
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'plop', serverUrl: serverUrl, filePath: path, headers: headers })
          } else if (upload.state === 'UPLOADED') {
            expect(upload.id).toBe('plop')
            var response = JSON.parse(upload.serverResponse)
            expect(response.receivedInfo.headers.signature).toBe('secret_hash')
            expect(response.receivedInfo.headers.source).toBe('test')
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          }
        })
      })

      it('sends parameters during upload', function (done) {
        var params = {
          role: 'tester',
          type: 'authenticated'
        }
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'xeon', serverUrl: serverUrl, filePath: path, parameters: params })
          } else if (upload.state === 'UPLOADED') {
            expect(upload.id).toBe('xeon')
            var response = JSON.parse(upload.serverResponse)
            expect(response.receivedInfo.parameters).toEqual(params)
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          }
        })
      })

      it('sends a FAILED event if upload fails', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'err_id', serverUrl: 'dummy_url', filePath: path })
          } else if (upload.state === 'FAILED') {
            expect(upload.id).toBe('err_id')
            expect(upload.error).toBeDefined()
            expect(upload.errorCode).toBeDefined()
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          }
        })
      })

      it('sends a FAILED callback if file does not exist', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'nox', serverUrl: serverUrl, filePath: '/path/fake.jpg' }, function () {}, function (upload) {
              expect(upload.id).toBe('nox')
              expect(upload.eventId).toBeUndefined()
              expect(upload.error).toContain('File not found')
              done()
            })
          }
        })
      })
    })

    describe('Multiple Upload', function () {
      var sampleFile2 = 'tree2.jpg'; var sampleFile3 = 'tree3.jpg'; var path2 = ''; var path3 = ''

      beforeEach(function (done) {
        TestUtils.copyFileToDataDirectory(sampleFile2).then(function (newPath2) {
          path2 = newPath2
          TestUtils.copyFileToDataDirectory(sampleFile3).then(function (newPath3) {
            path3 = newPath3
            done()
          })
        })
      })

      afterEach(function (done) {
        TestUtils.deleteFile(sampleFile2).then(function () {
          TestUtils.deleteFile(sampleFile3).then(done)
        })
      })

      it('can transfer in parallel', function (done) {
        var filesToUpload = ['file_1', 'file_2', 'file_3']
        var uploadedFiles = []
        nativeUploader = FileTransferManager.init({ parallelUploadsLimit: 3 }, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: filesToUpload[0], serverUrl: serverUrl, filePath: path })
            nativeUploader.startUpload({ id: filesToUpload[1], serverUrl: serverUrl, filePath: path2 })
            nativeUploader.startUpload({ id: filesToUpload[2], serverUrl: serverUrl, filePath: path3 })
          } else if (upload.state === 'UPLOADED') {
            expect(filesToUpload).toContain(upload.id)
            if (uploadedFiles.indexOf(upload.id) < 0 && filesToUpload.indexOf(upload.id) > -1) {
              uploadedFiles.push(upload.id)
              nativeUploader.acknowledgeEvent(upload.eventId, function () {
                if (uploadedFiles.length >= 3) done()
              })
            }
          }
        })
      })
    })

    describe('Remove upload', function () {
      it('should have removeUpload function', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          expect(nativeUploader.removeUpload).toBeDefined()
          done()
        })
      })

      it('returns an error if no uploadId is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.removeUpload(null, null, function (result) {
            expect(result.error).toBe('Upload ID is required')
            done()
          })
        })
      })

      it('returns an error if undefined uploadId is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.removeUpload(undefined, null, function (result) {
              expect(result.error).toBe('Upload ID is required')
              done()
            })
          }
        })
      })

      it('does not return error if uploadId is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.removeUpload('blob', function () {
              expect(true).toBeTruthy()
              done()
            }, null)
          }
        })
      })

      it('sends a FAILED callback when upload is removed', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'xyz', serverUrl: serverUrl, filePath: path })
          } else if (upload.state === 'FAILED') {
            expect(upload.id).toBe('xyz')
            expect(upload.eventId).toBeDefined()
            expect(upload.error).toContain('cancel')
            expect(upload.errorCode).toBe(-999)
            nativeUploader.acknowledgeEvent(upload.eventId, done)
          } else if (upload.state === 'UPLOADING') {
            nativeUploader.removeUpload('xyz', null, null)
          }
        })
      })
    })

    describe('Acknowledge event', function () {
      it('should have acknowledgeEvent function', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          expect(nativeUploader.acknowledgeEvent).toBeDefined()
          done()
        })
      })

      it('returns an error if no eventId is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function () {
          nativeUploader.acknowledgeEvent(null, null, function (result) {
            expect(result.error).toBe('Event ID is required')
            done()
          })
        })
      })

      it('returns an error if undefined eventId is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.acknowledgeEvent(undefined, null, function (result) {
              expect(result.error).toBe('Event ID is required')
              done()
            })
          }
        })
      })

      it('does not return error if eventId is given', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload) {
          if (upload.state === 'INITIALIZED') {
            nativeUploader.acknowledgeEvent('x-coredata://123/UploadEvent/p1', function () {
              expect(true).toBeTruthy()
              done()
            }, null)
          }
        })
      })

      it('persist event id until it is acknowledged', function (done) {
        nativeUploader = FileTransferManager.init({}, function (upload1) {
          if (upload1.state === 'INITIALIZED') {
            nativeUploader.startUpload({ id: 'unsub', serverUrl: serverUrl, filePath: path })
          } else if (upload1.state === 'UPLOADED') {
            nativeUploader.destroy()
            nativeUploader = FileTransferManager.init({}, function (upload2) {
              if (upload2.state !== 'INITIALIZED') {
                expect(upload2.id).toBe('unsub')
                expect(upload2.eventId).toBe(upload1.eventId)
                nativeUploader.acknowledgeEvent(upload2.eventId, done)
              }
            })
          }
        })
      })
    })
  })
}
