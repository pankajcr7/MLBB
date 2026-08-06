package com.pankaj.mlbbdraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pankaj.mlbbdraft.data.MetaRepository
import com.pankaj.mlbbdraft.data.ProfileStore
import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.ui.DraftScreen
import com.pankaj.mlbbdraft.ui.theme.MlbbDraftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The bundled dataset is the base layer and is loaded once here; live meta data
        // is applied over it by the ViewModel. The same instances will be shared with
        // the Phase 1 overlay service.
        val baseDb = DatasetLoader.fromResources()
        val profileStore = ProfileStore(applicationContext)
        val metaRepository = MetaRepository(applicationContext)

        setContent {
            MlbbDraftTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: DraftViewModel = viewModel {
                        DraftViewModel(baseDb, profileStore, metaRepository)
                    }
                    DraftScreen(viewModel)
                }
            }
        }
    }
}
