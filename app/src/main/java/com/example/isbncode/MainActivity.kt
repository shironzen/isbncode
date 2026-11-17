
package com.example.isbncode

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import androidx.compose.foundation.combinedClickable

// ---------- 1. Модель данных: одна книга ----------
data class Book(
    val isbn: String,
    val title: String,
    val author: String
)

// ---------- 2. Хранение: сохраняем список в памяти телефона ----------
// SharedPreferences - это простое хранилище "ключ -> строка".
// Список книг превращаем в JSON-текст и кладём под одним ключом.
private const val PREFS_NAME = "books_prefs"
private const val KEY_BOOKS = "books"

fun saveBooks(context: Context, books: List<Book>) {
    val array = JSONArray()
    for (book in books) {
        val obj = JSONObject()
        obj.put("isbn", book.isbn)
        obj.put("title", book.title)
        obj.put("author", book.author)
        array.put(obj)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_BOOKS, array.toString())
        .apply()
}

fun loadBooks(context: Context): List<Book> {
    val text = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_BOOKS, null) ?: return emptyList()
    val array = JSONArray(text)
    val result = mutableListOf<Book>()
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        result.add(Book(obj.getString("isbn"), obj.getString("title"), obj.getString("author")))
    }
    return result
}

// ---------- 3. Интернет: ищем название и автора по ISBN в Google Books ----------
// suspend + Dispatchers.IO = выполняем в фоне, чтобы не вешать экран.
suspend fun fetchBook(isbn: String): Book = withContext(Dispatchers.IO) {
    try {
        // Используем Open Library API (не блокируется в РФ)
        val text = URL("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data").readText()
        val json = JSONObject(text)

        val bookKey = "ISBN:$isbn"
        if (json.has(bookKey)) {
            val bookData = json.getJSONObject(bookKey)
            val title = bookData.optString("title", "Без названия")

            val authorsArray = bookData.optJSONArray("authors")
            val author = if (authorsArray == null) {
                ""
            } else {
                (0 until authorsArray.length()).joinToString(", ") {
                    authorsArray.getJSONObject(it).optString("name", "")
                }
            }
            Book(isbn, title, author)
        } else {
            Book(isbn, "Книга не найдена в базе", "")
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Book(isbn, "Ошибка сети", "")
    }
}

// ---------- 4. Экран ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BookCatalogScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCatalogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Когда меняются эти переменные, экран сам перерисовывается
    var books by remember { mutableStateOf(loadBooks(context)) }
    var message by remember { mutableStateOf("") }

    // Запуск сканера Google (камера открывается сама, разрешение не нужно)
    fun startScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13) // ISBN на книгах - это EAN-13
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val isbn = barcode.rawValue
                if (isbn == null) {
                    message = "Не удалось прочитать код"
                } else if (books.any { it.isbn == isbn }) {
                    message = "Эта книга уже в каталоге"
                } else {
                    message = "Ищу книгу..."
                    scope.launch {
                        val book = fetchBook(isbn)
                        books = books + book
                        saveBooks(context, books)
                        message = ""
                    }
                }
            }
            .addOnFailureListener {
                message = "Сканирование не удалось"
            }
    }

    // Функция для удаления книги
    fun deleteBook(bookToDelete: Book) {
        android.app.AlertDialog.Builder(context)
            .setTitle("Удалить книгу?")
            .setMessage("Вы уверены, что хотите удалить \"${bookToDelete.title}\"?")
            .setPositiveButton("Удалить") { _, _ ->
                books = books.filter { it.isbn != bookToDelete.isbn }
                saveBooks(context, books)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Мои книги") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            Button(
                onClick = { startScan() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сканировать ISBN")
            }

            if (message.isNotEmpty()) {
                Text(message, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn {
                items(books.sortedBy { it.title }) { book ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .combinedClickable(
                                onClick = { },
                                onLongClick = { deleteBook(book) }
                            )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                            if (book.author.isNotEmpty()) {
                                Text(book.author)
                            }
                            Text("ISBN: ${book.isbn}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}