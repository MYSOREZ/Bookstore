import os
import shutil

# Настройки
SOURCE_DIR = 'app/src/main/html_sources/'
ASSETS_DIR = 'app/src/main/assets/'
KEY = "MySecretKey123"

def xor_cipher(data, key):
    key = key.encode()
    return bytes([b ^ key[i % len(key)] for i, b in enumerate(data)])

def encrypt_and_copy():
    # Создаем папку assets, если её нет
    if not os.path.exists(ASSETS_DIR):
        os.makedirs(ASSETS_DIR)
    
    # Очищаем текущие файлы в assets перед обновлением
    for f in os.listdir(ASSETS_DIR):
        file_path = os.path.join(ASSETS_DIR, f)
        if os.path.isfile(file_path):
            os.remove(file_path)

    print(f"Starting encryption from {SOURCE_DIR} to {ASSETS_DIR}...")
    
    for filename in os.listdir(SOURCE_DIR):
        src_path = os.path.join(SOURCE_DIR, filename)
        if os.path.isfile(src_path):
            print(f"Encrypting {filename}...")
            with open(src_path, 'rb') as f:
                data = f.read()
            
            encrypted_data = xor_cipher(data, KEY)
            
            # Сохраняем с расширением .enc
            dst_path = os.path.join(ASSETS_DIR, filename + '.enc')
            with open(dst_path, 'wb') as f:
                f.write(encrypted_data)

if __name__ == "__main__":
    if os.path.exists(SOURCE_DIR):
        encrypt_and_copy()
        print("Done! All assets are encrypted and ready for APK build.")
    else:
        print(f"Error: Source directory {SOURCE_DIR} not found!")
