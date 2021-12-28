package com.asfoundation.wallet.ui.backup.save

import android.os.Build
import android.os.Environment
import com.asfoundation.wallet.ui.backup.save.use_cases.SaveBackupFileUseCase
import dagger.Module
import dagger.Provides
import java.io.File
import javax.inject.Named

@Module
class SaveBackupBottomSheetModule {

  @Provides
  fun providesSaveBackupBottomSheetViewModelFactory(
      saveBackupBottomSheetData: SaveBackupBottomSheetData,
      saveBackupFileUseCase: SaveBackupFileUseCase,
      @Named("downloads-path") downloadsPath: File?): SaveBackupBottomSheetViewModelFactory {
    return SaveBackupBottomSheetViewModelFactory(saveBackupBottomSheetData, saveBackupFileUseCase,
        downloadsPath)
  }

  @Provides
  fun providesSaveBackupBottomSheetNavigator(
      fragment: SaveBackupBottomSheetFragment
  ): SaveBackupBottomSheetNavigator {
    return SaveBackupBottomSheetNavigator(fragment, fragment.requireFragmentManager())
  }

  @Provides
  fun providesSaveBackupBottomSheetData(
      fragment: SaveBackupBottomSheetFragment): SaveBackupBottomSheetData {
    fragment.requireArguments()
        .apply {
          return SaveBackupBottomSheetData(
              getString(SaveBackupBottomSheetFragment.WALLET_ADDRESS_KEY)!!, getString(
              SaveBackupBottomSheetFragment.PASSWORD_KEY)!!)
        }
  }

  @Provides
  @Named("downloads-path")
  fun providesDownloadsPath(): File? {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS)
    else null
  }
}